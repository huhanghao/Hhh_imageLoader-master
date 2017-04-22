package com.huhanghao.hhh_imageloader;

import android.app.ProgressDialog;
import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.provider.MediaStore;
import android.support.v7.app.AppCompatActivity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.huhanghao.hhh_imageloader.bean.FolderBean;
import com.huhanghao.hhh_imageloader.util.ImageLoader;

import java.io.File;
import java.io.FilenameFilter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MainActivity extends AppCompatActivity {

    // 控件
    private GridView mGridView;
    private TextView mDirName;
    private TextView mDircount;
    private View bottomView;
    private ProgressDialog mProgressDialog;
    private ImageAdapter mImageAdapter;

    // 局部变量
    private List<String> mImages;
    private File mCurrentDir;
    private int mMaxCount;
    private List<FolderBean> mFolderBeans = new ArrayList<>();
    int test = 0;

    // 静态变量
    private static final int DATA_LOADED = 0X110;

    private Handler mHandler = new Handler(){
        @Override
        public void handleMessage(Message msg) {
            if (msg.what == DATA_LOADED)
            {
                mProgressDialog.dismiss();

                data2View();
            }
        }
    };


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 初始化view
        initView();
        // 初始化监听
        initListener();
        // 初始化数据
        initDatas();

    }

    private void initView() {
        bottomView = findViewById(R.id.rl_bottom_view);
        mGridView = (GridView) findViewById(R.id.id_gridView);
        mDirName = (TextView) findViewById(R.id.tv_dir_name);
        mDircount = (TextView) findViewById(R.id.tv_pic_count);
    }

    private void initListener() {


    }

    private void initDatas() {
        // 利用ContentProvider去扫描手机中的图片文件夹
        if (!Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)) {
            Toast.makeText(this, "当前存储卡不可用", Toast.LENGTH_SHORT);
            return;
        }
        mProgressDialog = ProgressDialog.show(this, null, "正在加载...");

        new Thread() {
            @Override
            public void run() {
                Uri mImageUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
                ContentResolver cr = MainActivity.this.getContentResolver();
                Cursor cursor = cr.query(mImageUri, null, MediaStore.Images.Media.MIME_TYPE + "= ? or" + MediaStore.Images.Media.MIME_TYPE + "= ?", new String[]{"image/jpeg","image/png"}, MediaStore.Images.Media.DATE_MODIFIED);

                Set<String> mDirPaths = new HashSet<>();
                while(cursor.moveToNext())
                {
                    String path = cursor.getString(cursor.getColumnIndex(MediaStore.Images.Media.DATA));
                    File parentFile = new File(path).getParentFile();
                    if(parentFile == null)
                    {
                        continue;
                    }
                    String dirPath = parentFile.getAbsolutePath();
                    FolderBean folderBean = null;

                   if(mDirPaths.contains(dirPath))
                   {
                       continue;
                   }else
                   {
                       mDirPaths.add(dirPath);
                       folderBean = new FolderBean();
                       folderBean.setDir(dirPath);
                       folderBean.setFirstImgPath(path);
                   }
                    if(parentFile.list()==null)
                    {
                        continue;
                    }
                    int picSize = parentFile.list(new FilenameFilter() {
                        @Override
                        public boolean accept(File dir, String filename) {
                            if(filename.endsWith(".jpg")||filename.endsWith(".jpeg")||filename.endsWith(".png"))
                                return true;
                            return false;
                        }
                    }).length;
                    folderBean.setCount(picSize);
                    mFolderBeans.add(folderBean);
                    if(picSize > mMaxCount)
                    {
                        mMaxCount = picSize;
                        mCurrentDir = parentFile;
                    }
                    cursor.close();
                    // 释放内存
                    mDirPaths = null;

                    mHandler.sendEmptyMessage(DATA_LOADED);
                }
            }
        }.start();
    }


    private void data2View() {
        if (mCurrentDir == null)
        {
            Toast.makeText(this,"未扫描到任何图片",Toast.LENGTH_SHORT).show();
            return;
        }
        mImages = Arrays.asList(mCurrentDir.list());
        mImageAdapter = new ImageAdapter(this,mImages,mCurrentDir.getAbsolutePath());
        mGridView.setAdapter(mImageAdapter);

        mDircount.setText(mMaxCount + "");
        mDirName.setText(mCurrentDir.getName());
    }

    private class ImageAdapter extends BaseAdapter
    {
        private String mDirPath;
        private List<String> mImgPaths;
        private LayoutInflater mInflator;

        public ImageAdapter(Context context, List<String>mDatas, String dirPath)
        {
            this.mDirPath = dirPath;
            this.mImgPaths = mDatas;
            mInflator = LayoutInflater.from(context);
        }

        @Override
        public int getCount() {
            return mImgPaths.size();
        }

        @Override
        public Object getItem(int position) {
            return mImgPaths.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ViewHolder viewHolder = null;
            if(convertView == null)
            {
                convertView = mInflator.inflate(R.layout.item_gridview,parent,false);
                viewHolder = new ViewHolder();
                viewHolder.mImageView = (ImageView) convertView.findViewById(R.id.iv_item_image);
                viewHolder.mImageButton = (ImageButton) convertView.findViewById(R.id.ib_item_select);
                convertView.setTag(viewHolder);
            }else
            {
                viewHolder = (ViewHolder) convertView.getTag();
            }
            // 充值状态
            viewHolder.mImageView.setImageResource(R.drawable.picture_no);
            viewHolder.mImageButton.setImageResource(R.drawable.pic_unselected);

            ImageLoader.getInstance(3, ImageLoader.Type.LIFO).loadImage(mDirPath + "/" + mImgPaths.get(position),viewHolder.mImageView);

            return convertView;
        }

        private class ViewHolder
        {
            ImageView mImageView;
            ImageButton mImageButton;
        }
    }
}
