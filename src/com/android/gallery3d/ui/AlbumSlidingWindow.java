/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.gallery3d.ui;

import com.android.gallery3d.app.GalleryActivity;
import com.android.gallery3d.common.BitmapUtils;
import com.android.gallery3d.common.LruCache;
import com.android.gallery3d.common.Utils;
import com.android.gallery3d.data.MediaItem;
import com.android.gallery3d.data.Path;
import com.android.gallery3d.util.Future;
import com.android.gallery3d.util.FutureListener;
import com.android.gallery3d.util.ThreadPool;
import com.android.gallery3d.util.ThreadPool.Job;
import com.android.gallery3d.util.ThreadPool.JobContext;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Message;

public class AlbumSlidingWindow implements AlbumView.ModelListener {
    @SuppressWarnings("unused")
    private static final String TAG = "AlbumSlidingWindow";

    private static final int MSG_LOAD_BITMAP_DONE = 0;
    private static final int MSG_UPDATE_SLOT = 1;
    private static final int MIN_THUMB_SIZE = 100;

    public static interface Listener {
        public void onSizeChanged(int size);
        public void onContentInvalidated();
        public void onWindowContentChanged(
                int slot, DisplayItem old, DisplayItem update);
    }

    private final AlbumView.Model mSource;
    private int mSize;

    private int mContentStart = 0;
    private int mContentEnd = 0;

    private int mActiveStart = 0;
    private int mActiveEnd = 0;

    private Listener mListener;
    private int mFocusIndex = -1;

    private final AlbumDisplayItem mData[];
    private final ColorTexture mWaitLoadingTexture;
    private SelectionDrawer mSelectionDrawer;

    private SynchronizedHandler mHandler;
    private ThreadPool mThreadPool;
    private int mSlotWidth, mSlotHeight;

    private int mActiveRequestCount = 0;
    private boolean mIsActive = false;

    private int mDisplayItemSize;  // 0: disabled
    private LruCache<Path, Bitmap> mImageCache = new LruCache<Path, Bitmap>(1000);

    public AlbumSlidingWindow(GalleryActivity activity,
            AlbumView.Model source, int cacheSize,
            int slotWidth, int slotHeight, int displayItemSize) {
        source.setModelListener(this);
        mSource = source;
        mData = new AlbumDisplayItem[cacheSize];
        mSize = source.size();
        mSlotWidth = slotWidth;
        mSlotHeight = slotHeight;
        mDisplayItemSize = displayItemSize;

        mWaitLoadingTexture = new ColorTexture(Color.TRANSPARENT);
        mWaitLoadingTexture.setSize(1, 1);

        mHandler = new SynchronizedHandler(activity.getGLRoot()) {
            @Override
            public void handleMessage(Message message) {
                switch (message.what) {
                    case MSG_LOAD_BITMAP_DONE: {
                        ((AlbumDisplayItem) message.obj).onLoadBitmapDone();
                        break;
                    }
                    case MSG_UPDATE_SLOT: {
                        updateSlotContent(message.arg1);
                        break;
                    }
                }
            }
        };

        mThreadPool = activity.getThreadPool();
    }

    public void setSelectionDrawer(SelectionDrawer drawer) {
        mSelectionDrawer = drawer;
    }

    public void setListener(Listener listener) {
        mListener = listener;
    }

    public void setFocusIndex(int slotIndex) {
        mFocusIndex = slotIndex;
    }

    public DisplayItem get(int slotIndex) {
        Utils.assertTrue(isActiveSlot(slotIndex),
                "invalid slot: %s outsides (%s, %s)",
                slotIndex, mActiveStart, mActiveEnd);
        return mData[slotIndex % mData.length];
    }

    public int size() {
        return mSize;
    }

    public boolean isActiveSlot(int slotIndex) {
        return slotIndex >= mActiveStart && slotIndex < mActiveEnd;
    }

    private void setContentWindow(int contentStart, int contentEnd) {
        if (contentStart == mContentStart && contentEnd == mContentEnd) return;

        if (!mIsActive) {
            mContentStart = contentStart;
            mContentEnd = contentEnd;
            mSource.setActiveWindow(contentStart, contentEnd);
            return;
        }

        if (contentStart >= mContentEnd || mContentStart >= contentEnd) {
            for (int i = mContentStart, n = mContentEnd; i < n; ++i) {
                freeSlotContent(i);
            }
            mSource.setActiveWindow(contentStart, contentEnd);
            for (int i = contentStart; i < contentEnd; ++i) {
                prepareSlotContent(i);
            }
        } else {
            for (int i = mContentStart; i < contentStart; ++i) {
                freeSlotContent(i);
            }
            for (int i = contentEnd, n = mContentEnd; i < n; ++i) {
                freeSlotContent(i);
            }
            mSource.setActiveWindow(contentStart, contentEnd);
            for (int i = contentStart, n = mContentStart; i < n; ++i) {
                prepareSlotContent(i);
            }
            for (int i = mContentEnd; i < contentEnd; ++i) {
                prepareSlotContent(i);
            }
        }

        mContentStart = contentStart;
        mContentEnd = contentEnd;
    }

    public void setActiveWindow(int start, int end) {
        Utils.assertTrue(start <= end
                && end - start <= mData.length && end <= mSize,
                "%s, %s, %s, %s", start, end, mData.length, mSize);
        DisplayItem data[] = mData;

        mActiveStart = start;
        mActiveEnd = end;

        // If no data is visible, keep the cache content
        if (start == end) return;

        int contentStart = Utils.clamp((start + end) / 2 - data.length / 2,
                0, Math.max(0, mSize - data.length));
        int contentEnd = Math.min(contentStart + data.length, mSize);
        setContentWindow(contentStart, contentEnd);
        if (mIsActive) updateAllImageRequests();
    }

    // We would like to request non active slots in the following order:
    // Order:    8 6 4 2                   1 3 5 7
    //         |---------|---------------|---------|
    //                   |<-  active  ->|
    //         |<-------- cached range ----------->|
    private void requestNonactiveImages() {
        int range = Math.max(
                (mContentEnd - mActiveEnd), (mActiveStart - mContentStart));
        for (int i = 0 ;i < range; ++i) {
            requestSlotImage(mActiveEnd + i, false);
            requestSlotImage(mActiveStart - 1 - i, false);
        }
    }

    private void requestSlotImage(int slotIndex, boolean isActive) {
        if (slotIndex < mContentStart || slotIndex >= mContentEnd) return;
        AlbumDisplayItem item = mData[slotIndex % mData.length];
        item.requestImage();
    }

    private void cancelNonactiveImages() {
        int range = Math.max(
                (mContentEnd - mActiveEnd), (mActiveStart - mContentStart));
        for (int i = 0 ;i < range; ++i) {
            cancelSlotImage(mActiveEnd + i, false);
            cancelSlotImage(mActiveStart - 1 - i, false);
        }
    }

    private void cancelSlotImage(int slotIndex, boolean isActive) {
        if (slotIndex < mContentStart || slotIndex >= mContentEnd) return;
        AlbumDisplayItem item = mData[slotIndex % mData.length];
        item.cancelImageRequest();
    }

    private void freeSlotContent(int slotIndex) {
        AlbumDisplayItem data[] = mData;
        int index = slotIndex % data.length;
        AlbumDisplayItem original = data[index];
        if (original != null) {
            original.recycle();
            data[index] = null;
        }
    }

    private void prepareSlotContent(final int slotIndex) {
        mData[slotIndex % mData.length] = new AlbumDisplayItem(
                slotIndex, mSource.get(slotIndex));
    }

    private void updateSlotContent(final int slotIndex) {
        MediaItem item = mSource.get(slotIndex);
        AlbumDisplayItem data[] = mData;
        int index = slotIndex % data.length;
        AlbumDisplayItem original = data[index];
        AlbumDisplayItem update = new AlbumDisplayItem(slotIndex, item);
        data[index] = update;
        boolean isActive = isActiveSlot(slotIndex);
        if (mListener != null && isActive) {
            mListener.onWindowContentChanged(slotIndex, original, update);
        }
        if (original != null) {
            if (isActive && original.isRequestInProgress()) {
                --mActiveRequestCount;
            }
            original.recycle();
        }
        if (isActive) {
            if (mActiveRequestCount == 0) cancelNonactiveImages();
            ++mActiveRequestCount;
            update.requestImage();
        } else {
            if (mActiveRequestCount == 0) update.requestImage();
        }
    }

    private void updateAllImageRequests() {
        mActiveRequestCount = 0;
        AlbumDisplayItem data[] = mData;
        for (int i = mActiveStart, n = mActiveEnd; i < n; ++i) {
            AlbumDisplayItem item = data[i % data.length];
            item.requestImage();
            if (item.isRequestInProgress()) ++mActiveRequestCount;
        }
        if (mActiveRequestCount == 0) {
            requestNonactiveImages();
        } else {
            cancelNonactiveImages();
        }
    }

    private class AlbumDisplayItem extends AbstractDisplayItem
            implements FutureListener<Bitmap>, Job<Bitmap> {
        private Future<Bitmap> mFuture;
        private final int mSlotIndex;
        private final int mMediaType;
        private Texture mContent;

        public AlbumDisplayItem(int slotIndex, MediaItem item) {
            super(item);
            mMediaType = (item == null)
                    ? MediaItem.MEDIA_TYPE_UNKNOWN
                    : item.getMediaType();
            mSlotIndex = slotIndex;
            updateContent(mWaitLoadingTexture);
        }

        @Override
        protected void onBitmapAvailable(Bitmap bitmap) {
            boolean isActiveSlot = isActiveSlot(mSlotIndex);
            if (isActiveSlot) {
                --mActiveRequestCount;
                if (mActiveRequestCount == 0) requestNonactiveImages();
            }
            if (bitmap != null) {
                BitmapTexture texture = new BitmapTexture(bitmap);
                texture.setThrottled(true);
                updateContent(texture);
                if (mListener != null && isActiveSlot) {
                    mListener.onContentInvalidated();
                }
            }
        }

        private void updateContent(Texture content) {
            mContent = content;

            int width = mContent.getWidth();
            int height = mContent.getHeight();

            float scalex = mDisplayItemSize / (float) width;
            float scaley = mDisplayItemSize / (float) height;
            float scale = Math.min(scalex, scaley);

            width = (int) Math.floor(width * scale);
            height = (int) Math.floor(height * scale);

            setSize(width, height);
        }

        @Override
        public boolean render(GLCanvas canvas, int pass) {
            if (pass == 0) {
                Path path = null;
                if (mMediaItem != null) path = mMediaItem.getPath();
                mSelectionDrawer.draw(canvas, mContent, mWidth, mHeight,
                        getRotation(), path, mMediaType);
                return (mFocusIndex == mSlotIndex);
            } else if (pass == 1) {
                mSelectionDrawer.drawFocus(canvas, mWidth, mHeight);
            }
            return false;
        }

        @Override
        public void startLoadBitmap() {
            if (mDisplayItemSize < MIN_THUMB_SIZE) {
                Path path = mMediaItem.getPath();
                if (mImageCache.containsKey(path)) {
                    Bitmap bitmap = mImageCache.get(path);
                    updateImage(bitmap, false);
                    return;
                }
                mFuture = mThreadPool.submit(this, this);
            } else {
                mFuture = mThreadPool.submit(mMediaItem.requestImage(
                        MediaItem.TYPE_MICROTHUMBNAIL), this);
            }
        }

        // This gets the bitmap and scale it down.
        public Bitmap run(JobContext jc) {
            Job<Bitmap> job = mMediaItem.requestImage(
                    MediaItem.TYPE_MICROTHUMBNAIL);
            Bitmap bitmap = job.run(jc);
            if (bitmap != null) {
                bitmap = BitmapUtils.resizeDownBySideLength(
                        bitmap, mDisplayItemSize, true);
            }
            return bitmap;
        }

        @Override
        public void cancelLoadBitmap() {
            if (mFuture != null) {
                mFuture.cancel();
            }
        }

        @Override
        public void onFutureDone(Future<Bitmap> bitmap) {
            mHandler.sendMessage(mHandler.obtainMessage(MSG_LOAD_BITMAP_DONE, this));
        }

        private void onLoadBitmapDone() {
            Future<Bitmap> future = mFuture;
            mFuture = null;
            Bitmap bitmap = future.get();
            boolean isCancelled = future.isCancelled();
            if (mDisplayItemSize < MIN_THUMB_SIZE && (bitmap != null || !isCancelled)) {
                Path path = mMediaItem.getPath();
                mImageCache.put(path, bitmap);
            }
            updateImage(bitmap, isCancelled);
        }

        @Override
        public String toString() {
            return String.format("AlbumDisplayItem[%s]", mSlotIndex);
        }
    }

    public void onSizeChanged(int size) {
        if (mSize != size) {
            mSize = size;
            if (mListener != null) mListener.onSizeChanged(mSize);
        }
    }

    public void onWindowContentChanged(int index) {
        if (index >= mContentStart && index < mContentEnd && mIsActive) {
            updateSlotContent(index);
        }
    }

    public void resume() {
        mIsActive = true;
        for (int i = mContentStart, n = mContentEnd; i < n; ++i) {
            prepareSlotContent(i);
        }
        updateAllImageRequests();
    }

    public void pause() {
        mIsActive = false;
        for (int i = mContentStart, n = mContentEnd; i < n; ++i) {
            freeSlotContent(i);
        }
        mImageCache.clear();
    }
}
