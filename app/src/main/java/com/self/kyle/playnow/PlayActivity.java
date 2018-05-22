package com.self.kyle.playnow;

import android.annotation.TargetApi;
import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v4.view.ViewCompat;
import android.support.v7.app.AppCompatActivity;
import android.transition.Transition;
import android.view.View;

import com.self.kyle.listener.OnTransitionListener;
import com.self.kyle.widget.EmptyControlVideo;
import com.shuyu.gsyvideoplayer.GSYVideoManager;

/**
 * Created by yxb on 2018/5/22.
 */
public class PlayActivity extends AppCompatActivity {
    private EmptyControlVideo video;
    private String rtmpUrl = "rtmp://bcs.iuuyun.com:1935/live/a460c48e-f6ec-4b1f-ac03-85ee3caf3f13";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_play);
        video = findViewById(R.id.emptyControlVideo);
        play();
    }

    public void play() {
        video.setUp(rtmpUrl, true, "");
        initTransition();
    }

    private boolean isTransition = true;
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
        video.setVideoAllCallBack(null);
        GSYVideoManager.releaseAllVideos();
    }
}
