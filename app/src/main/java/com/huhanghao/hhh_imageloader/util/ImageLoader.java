package com.huhanghao.hhh_imageloader.util;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.DisplayMetrics;
import android.util.LruCache;
import android.view.ViewGroup.LayoutParams;
import android.widget.ImageView;

import java.lang.reflect.Field;
import java.util.LinkedList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

/**
 * 用作imageView大量的图片加载，内部用线程池维护，并通过三级缓存进行存储
 * Created by huhanghao on 2016/5/7.
 */
public class ImageLoader {

    // 变量对象
    private static ImageLoader mInstance;
    private LruCache<String,Bitmap>mLruCache; // 缓存对象
    private ExecutorService mThreadPool;     // 线程池；加载图片任务
    private Type mType = Type.LIFO;           // 队列的调度方式，任务调度
    private LinkedList<Runnable> mTaskQueue; // 任务队列;
    private Thread mPoolThread;                 // 后台轮询线程
    private Handler mPoolThreadHandler;        // 为轮询线程发送消息
    private Handler mUIHandler;                 // 用于UI回调显示线程
    private Semaphore mSemaphorePoolThreadHandler = new Semaphore(0); // 同步下载线程的生成顺序（在起动handler之后才去调用handler处理msg）
    private Semaphore mSemaphorePoolThreadPool; // 控制下载任务数量的信号量
    // 静态数据
    private static final int DEAFULT_THREAD_COUNT = 1;
    public enum Type
    {
        FIFO,LIFO;
    }

    private ImageLoader(int mThreadCount , Type type)
    {
        init(mThreadCount,type);
    }

    /**
     * 初始化操作
     * @param mThreadCount
     * @param type
     */
    private void init(int mThreadCount, Type type) {
        // 后台轮询线程
        mPoolThread = new Thread()
        {
            @Override
            public void run() {
                Looper.prepare();
                mPoolThreadHandler = new Handler()
                {
                    @Override
                    public void handleMessage(Message msg) {
                        // 通过线程池去除任务进行执行
                        mThreadPool.execute(getTask());
                        try {
                            mSemaphorePoolThreadPool.acquire();
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }
                };
                // 释放一个信号量
                mSemaphorePoolThreadHandler.release();
                Looper.loop();
            }
        };
        mPoolThread.start();

        // 为lrucache设置最大可用内存数
        int maxMemory = (int) Runtime.getRuntime().maxMemory();
        int cacheMemory = maxMemory/8;
        mLruCache = new LruCache<String, Bitmap>(cacheMemory)
        {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getRowBytes()*value.getHeight();
            }
        };

        // 初始化
        mThreadPool = Executors.newFixedThreadPool(mThreadCount);
        mTaskQueue = new LinkedList<Runnable>();
        mType = type;

        // 初始化下载线程信号量
        mSemaphorePoolThreadPool = new Semaphore(mThreadCount);
    }

    private Runnable getTask() {
        if(mType == Type.FIFO)
        {
            return mTaskQueue.removeFirst();
        }else if(mType == Type.LIFO)
        {
            return mTaskQueue.removeFirst();
        }
        return null;
    }

    public static ImageLoader getInstance()
    {
        if(mInstance == null)
        {
            synchronized (ImageLoader.class)
            {
                mInstance = new ImageLoader(DEAFULT_THREAD_COUNT,Type.FIFO);
            }
        }
        return mInstance;
    }

    public static ImageLoader getInstance(int threadCount,Type type)
    {
        if(mInstance == null)
        {
            synchronized (ImageLoader.class)
            {
                mInstance = new ImageLoader(threadCount,type);
            }
        }
        return mInstance;
    }

    /**
     * 更具path为ImageView设置图片
     * @param path
     * @param imageView
     */
    public void loadImage(final String path, final ImageView imageView)
    {
        imageView.setTag(path);

        if(mUIHandler == null)
        {
            mUIHandler = new Handler()
            {
                @Override
                public void handleMessage(Message msg) {
                    // 获取的图片，为imageView加载设置图片
                    ImageBeanHolder holder = (ImageBeanHolder) msg.obj;
                    Bitmap bm = holder.bitmap;
                    ImageView imageView = holder.imageView;
                    String path = holder.path;
                    // 将path与getTag存储路径进行比较
                    if(imageView.getTag().toString().equals(path))
                    {
                        imageView.setImageBitmap(bm);
                    }
                }
            };
        }
        // 从Lru获取bitmap
        Bitmap bm = getBitmapFromLruCache(path);
        if(bm != null)
        {
            refreshBitmap(bm, path, imageView);
        }else
        {
            // 当缓存中没有时，开启线程进行网络下载
            addTasks(new Runnable()
            {
                @Override
                public void run() {
                    // 图片的压缩
                    // 1、获得图片需要显示的宽高（更具imageview的宽高获取）
                    ImageSize imageSize = getImageSize(imageView);
                    // 2、压缩图片
                    Bitmap bm = decodeSampledBitmapFromPath(path,imageSize.width,imageSize.height);
                    // 3、将图片加入到缓存中
                    addBitmapToLruCache(path,bm);
                    // 4、将图片放入imageview中进行刷新
                    refreshBitmap(bm, path, imageView);
                    // 5、当该线程被执行之后进行线程的释放
                    mSemaphorePoolThreadPool.release();
                }
            });
        }
    }

    /**
     * 更新imageview控件
     * @param bm
     * @param path
     * @param imageView
     */
    private void refreshBitmap(Bitmap bm, String path, ImageView imageView) {
        Message message = Message.obtain();
        ImageBeanHolder holder = new ImageBeanHolder();
        holder.bitmap = bm;
        holder.path = path;
        holder.imageView = imageView;
        message.obj = holder;
        mUIHandler.sendMessage(message);
    }

    /**
     * 将bitmap加入到缓存中
     * @param path
     * @param bm
     */
    private void addBitmapToLruCache(String path, Bitmap bm) {
        if(getBitmapFromLruCache(path) == null)
        {
         if(bm != null)
         {
             mLruCache.put(path,bm);
         }
        }
    }

    /**
     * 根据图片显示需要的宽高进行压缩
     * @param path
     * @param width
     * @param height
     * @return
     */
    private Bitmap decodeSampledBitmapFromPath(String path, int width, int height) {
        // 获取图片的宽高，并不把图片加载到内存中
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path,options);

        // 获取图片的宽高并根据显示需要进行压缩
        options.inSampleSize = caculateInSampleSize(options,width,height);

        // 使用获取到的inSampleSize再次解析图片
        options.inJustDecodeBounds = false;
        Bitmap bitmap = BitmapFactory.decodeFile(path, options);

        return bitmap;
    }

    /**
     * 获取图片的宽高并根据显示需要进行压缩,获得压缩的比例
     * @param options
     * @param reqWidth
     * @param reqHeight
     * @return
     */
    private int caculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
        int width = options.outWidth;
        int height = options.outHeight;
        int inSampleSize = 1;

        if((width > reqWidth) || (height > reqHeight))
        {
            int widthRadio = Math.round(width*1.0f/reqWidth);
            int heightRadio = Math.round(height*1.0f/reqHeight);

            inSampleSize = Math.max(widthRadio,heightRadio);
        }

        return inSampleSize;
    }

    /**
     * 更具imageView获取适当的宽高
     * @param imageView
     * @return
     */
    private ImageSize getImageSize(ImageView imageView) {
        ImageSize imageSiz = new ImageSize();
        DisplayMetrics displayMetrics = imageView.getContext().getResources().getDisplayMetrics();

        LayoutParams lp = imageView.getLayoutParams();

        // 获取宽度值
        int width = imageView.getWidth();
        if(width <= 0) //
        {
            width = lp.width; // 获取imageView在layout中申明的宽度
        }
        if(width <= 0)
        {
            width = getImageViewFieldValue(imageView,"mMaxWidth");  // 获取imageView最大的宽度
        }
        if(width<=0)
        {
            width = displayMetrics.widthPixels; // 获取屏幕的宽度
        }

        // 获取高度值
        int height = imageView.getHeight();
        if(height <= 0) //
        {
            height = lp.height; // 获取imageView在layout中申明的高度
        }
        if(height <= 0)
        {
            height = getImageViewFieldValue(imageView,"mMaxHeight");  // 获取imageView最大的高度
        }
        if(height<=0)
        {
            height = displayMetrics.heightPixels; // 获取屏幕的高度
        }

        imageSiz.width = width;
        imageSiz.height = height;

        return imageSiz;
    }

    /**
     * 通过反射获取obj对应的fieldName值
     * @param obj
     * @param fieldName
     * @return
     */
    private static int getImageViewFieldValue(Object obj,String fieldName)
    {
        int value = 0;
        try {
            Field field = ImageView.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            int fieldValue = field.getInt(obj);
            if((fieldValue > 0) && (fieldValue<Integer.MAX_VALUE))
            {
                value = fieldValue;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return value;
    }

    /**
     * 将下载任务放入线程池中（这里使用了其他线程的对象，为了防止死锁，设置为同步代码块）
     * @param runnable
     */
    private synchronized void addTasks(Runnable runnable) {
        mTaskQueue.add(runnable);

        // 信号量同步
        try {
            if(mPoolThreadHandler == null)
            {
                mSemaphorePoolThreadHandler.acquire();
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        // 后台线程池取任务进行执行
        mPoolThreadHandler.sendEmptyMessage(0x110);
    }

    /**
     * 从Lrucache中获取缓存的图片
     * @param path
     * @return
     */
    private Bitmap getBitmapFromLruCache(String path) {


        return mLruCache.get(path);

    }

    private class ImageSize
    {
        int width;
        int height;
    }

    /**
     * 用作图片加载的holder
     */
    private class ImageBeanHolder
    {
        Bitmap bitmap;
        ImageView imageView;
        String path;
    }
}
