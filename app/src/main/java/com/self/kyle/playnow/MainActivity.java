package com.self.kyle.playnow;

import android.Manifest;
import android.annotation.TargetApi;
import android.os.Build;
import android.support.v4.view.ViewCompat;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.transition.Transition;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import com.github.faucamp.simplertmp.RtmpHandler;
import com.self.kyle.listener.OnTransitionListener;
import com.self.kyle.widget.EmptyControlVideo;
import com.seu.magicfilter.utils.MagicFilterType;
import com.shuyu.gsyvideoplayer.GSYVideoManager;

import net.ossrs.yasea.SrsCameraView;
import net.ossrs.yasea.SrsEncodeHandler;
import net.ossrs.yasea.SrsPublisher;
import net.ossrs.yasea.SrsRecordHandler;

import java.io.IOException;
import java.net.SocketException;

public class MainActivity extends AppCompatActivity{
    private SrsCameraView cameraView;
    private SrsPublisher mPublisher;
    private EmptyControlVideo video;

    private String rtmpUrl = "rtmp://192.168.1.149:1935/live/stream";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);
        cameraView = findViewById(R.id.glsurfaceview_camera);
        video = findViewById(R.id.emptyControlVideo);
        initPublish(cameraView);
    }

    private void initPublish(SrsCameraView cameraView) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, 100);
        }
        mPublisher = new SrsPublisher(cameraView);
        //编码状态回调
        mPublisher.setEncodeHandler(new SrsEncodeHandler(new SrsEncodeHandler.SrsEncodeListener() {
            @Override
            public void onNetworkWeak() {

            }

            @Override
            public void onNetworkResume() {

            }

            @Override
            public void onEncodeIllegalArgumentException(IllegalArgumentException e) {

            }
        }));
        //录制回调
        mPublisher.setRecordHandler(new SrsRecordHandler(new SrsRecordHandler.SrsRecordListener() {
            @Override
            public void onRecordPause() {

            }

            @Override
            public void onRecordResume() {

            }

            @Override
            public void onRecordStarted(String msg) {

            }

            @Override
            public void onRecordFinished(String msg) {

            }

            @Override
            public void onRecordIllegalArgumentException(IllegalArgumentException e) {

            }

            @Override
            public void onRecordIOException(IOException e) {

            }
        }));
        //rtmp推流状态回调
        mPublisher.setRtmpHandler(new RtmpHandler(new RtmpHandler.RtmpListener() {
            @Override
            public void onRtmpConnecting(String msg) {
                Log.e("aaa", "开始：" + msg);
            }

            @Override
            public void onRtmpConnected(String msg) {
                Log.e("aaa", "已连接：" + msg);
            }

            @Override
            public void onRtmpVideoStreaming() {
                Log.e("aaa", "传输视频中。。。。" );
            }

            @Override
            public void onRtmpAudioStreaming() {
                Log.e("aaa", "传输音频中。。。。" );
            }

            @Override
            public void onRtmpStopped() {
                Log.e("aaa", "结束" );
            }

            @Override
            public void onRtmpDisconnected() {
                Log.e("aaa", "断开连接" );
            }

            @Override
            public void onRtmpVideoFpsChanged(double fps) {

            }

            @Override
            public void onRtmpVideoBitrateChanged(double bitrate) {

            }

            @Override
            public void onRtmpAudioBitrateChanged(double bitrate) {

            }

            @Override
            public void onRtmpSocketException(SocketException e) {
                Log.e("aaa", "网络失败：" + e );
            }

            @Override
            public void onRtmpIOException(IOException e) {
                Log.e("aaa", "传输失败：" + e );
            }

            @Override
            public void onRtmpIllegalArgumentException(IllegalArgumentException e) {

            }

            @Override
            public void onRtmpIllegalStateException(IllegalStateException e) {

            }
        }));
        //预览分辨率
        mPublisher.setPreviewResolution(1280, 720);
        //推流分辨率
        mPublisher.setOutputResolution(720, 1280);
        //传输率
        mPublisher.setVideoHDMode();
        //开启美颜（其他滤镜效果在MagicFilterType中查看）
        mPublisher.switchCameraFilter(MagicFilterType.BEAUTY);

    }

    public void preview(View view) {
        //打开摄像头，开始预览（未推流）
        mPublisher.startCamera();
    }

    public void push(View view) {
//        mPublisher.switchToSoftEncoder();//选择软编码
        mPublisher.switchToHardEncoder();//选择硬编码
        //开始推流 rtmpUrl
        mPublisher.startPublish(rtmpUrl);
    }

    public void accept(View view) {
        video.setUp(rtmpUrl, true, "");
//        video.setUp("http://clips.vorwaerts-gmbh.de/big_buck_bunny.mp4", true, "");
        initTransition();
    }

    private boolean isTransition;
    private Transition transition;
    public final static String IMG_TRANSITION = "IMG_TRANSITION";

    private void initTransition() {
        if (isTransition && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            postponeEnterTransition();
            ViewCompat.setTransitionName(video, IMG_TRANSITION);
            addTransitionListener();
            startPostponedEnterTransition();
        } else {
            video.startPlayLogic();
        }
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private boolean addTransitionListener() {
        transition = getWindow().getSharedElementEnterTransition();
        if (transition != null) {
            transition.addListener(new OnTransitionListener(){
                @Override
                public void onTransitionEnd(Transition transition) {
                    super.onTransitionEnd(transition);
                    video.startPlayLogic();
                    transition.removeListener(this);
                }
            });
            return true;
        }
        return false;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mPublisher.stopPublish();
        mPublisher.stopRecord();
        video.setVideoAllCallBack(null);
        GSYVideoManager.releaseAllVideos();
    }
}
