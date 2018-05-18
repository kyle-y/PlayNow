package net.ossrs.yasea;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.util.AttributeSet;

import com.seu.magicfilter.base.gpuimage.GPUImageFilter;
import com.seu.magicfilter.utils.MagicFilterFactory;
import com.seu.magicfilter.utils.MagicFilterType;
import com.seu.magicfilter.utils.OpenGLUtils;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Created by Leo Ma on 2016/2/25.
 * SurfaceView也可用来预览Camera，那么这两者有什么区别吗？
 * GLSurfaceView能够真正做到让Camera的数据和显示分离，我们就可以在此基础上对视频数据做一些处理，例如美图，增加特效等
 * 必须实现GLSurfaceView.Renderer以获得回调
 * 使用OPENGL2.0 所以要全程关注GLES20
 */
public class SrsCameraView extends GLSurfaceView implements GLSurfaceView.Renderer {

    private GPUImageFilter magicFilter;
    private SurfaceTexture surfaceTexture;
    private int mOESTextureId = OpenGLUtils.NO_TEXTURE;
    private int mSurfaceWidth;
    private int mSurfaceHeight;
    private int mPreviewWidth;
    private int mPreviewHeight;
    private volatile boolean mIsEncoding;
    private boolean mIsTorchOn = false;
    private float mInputAspectRatio;
    private float mOutputAspectRatio;
    private float[] mProjectionMatrix = new float[16];
    private float[] mSurfaceMatrix = new float[16];
    private float[] mTransformMatrix = new float[16];

    private Camera mCamera;
    private ByteBuffer mGLPreviewBuffer;
    private int mCamId = -1;
    private int mPreviewRotation = 90;
    private int mPreviewOrientation = Configuration.ORIENTATION_PORTRAIT;

    private Thread worker;
    private final Object writeLock = new Object();
    private ConcurrentLinkedQueue<IntBuffer> mGLIntBufferCache = new ConcurrentLinkedQueue<>();
    private PreviewCallback mPrevCb;

    public SrsCameraView(Context context) {
        this(context, null);
    }

    public SrsCameraView(Context context, AttributeSet attrs) {
        super(context, attrs);
        // 设置OpenGl ES的版本为2.0（因为Android支持OpenGL ES1.1、2.0以及3.+等版本，而且版本间的差别很大，不声明版本号，GLSurfaceView是不知道使用哪个版本进行渲染）
        setEGLContextClientVersion(2);
        // 设置与当前GLSurfaceView绑定的Renderer
        setRenderer(this);
        // 设置渲染的模式，需要时render，RENDERMODE_CONTINUOUSLY一直渲染
        setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        GLES20.glDisable(GL10.GL_DITHER);
        GLES20.glClearColor(0, 0, 0, 0);
        //初始化滤镜
        magicFilter = new GPUImageFilter(MagicFilterType.NONE);
        magicFilter.init(getContext().getApplicationContext());
        magicFilter.onInputSizeChanged(mPreviewWidth, mPreviewHeight);

        mOESTextureId = OpenGLUtils.getExternalOESTextureID();
        surfaceTexture = new SurfaceTexture(mOESTextureId); //创建纹理
        surfaceTexture.setOnFrameAvailableListener(new SurfaceTexture.OnFrameAvailableListener() {
            @Override
            public void onFrameAvailable(SurfaceTexture surfaceTexture) {
                requestRender(); //当有帧数据时，请求渲染
            }
        });

        // For camera preview on activity creation
        if (mCamera != null) {
            try {
                mCamera.setPreviewTexture(surfaceTexture);//将纹理作为渲染的载体
            } catch (IOException ioe) {
                ioe.printStackTrace();
            }
        }
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        // 设置OpenGL场景的大小,(0,0)表示窗口内部视口的左下角，(w,h)指定了视口的大小
        GLES20.glViewport(0, 0, width, height);
        mSurfaceWidth = width;
        mSurfaceHeight = height;
        //重新设置滤镜显示大小
        magicFilter.onDisplaySizeChanged(width, height);
        //得到输出宽高比
        mOutputAspectRatio = width > height ? (float) width / height : (float) height / width;
        float aspectRatio = mOutputAspectRatio / mInputAspectRatio;
        if (width > height) {
            Matrix.orthoM(mProjectionMatrix, 0, -1.0f, 1.0f, -aspectRatio, aspectRatio, -1.0f, 1.0f);
        } else {
            Matrix.orthoM(mProjectionMatrix, 0, -aspectRatio, aspectRatio, -1.0f, 1.0f, -1.0f, 1.0f);
        }
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        // 设置透明为清屏
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
        // 清除屏幕和深度缓存
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
        // 更新纹理
        surfaceTexture.updateTexImage();
        //更新滤镜
        surfaceTexture.getTransformMatrix(mSurfaceMatrix);
        Matrix.multiplyMM(mTransformMatrix, 0, mSurfaceMatrix, 0, mProjectionMatrix, 0);
        magicFilter.setTextureTransformMatrix(mTransformMatrix);
        magicFilter.onDrawFrame(mOESTextureId);

        if (mIsEncoding) {
            //将合成后的frame数据添加到缓存队列
            mGLIntBufferCache.add(magicFilter.getGLFboBuffer());
            synchronized (writeLock) {
                writeLock.notifyAll();
            }
        }
    }

    public void setPreviewCallback(PreviewCallback cb) {
        mPrevCb = cb;  //设置预览监听，返回预览的frame数据
    }

    public int[] setPreviewResolution(int width, int height) {
        getHolder().setFixedSize(width, height);

        mCamera = openCamera();
        mPreviewWidth = width;
        mPreviewHeight = height;
        Camera.Size rs = adaptPreviewResolution(mCamera.new Size(width, height));
        if (rs != null) {
            mPreviewWidth = rs.width;
            mPreviewHeight = rs.height;
        }
        //设置预览尺寸
        mCamera.getParameters().setPreviewSize(mPreviewWidth, mPreviewHeight);
        //默认每个像素占4byte，开辟相应的缓存空间
        mGLPreviewBuffer = ByteBuffer.allocateDirect(mPreviewWidth * mPreviewHeight * 4);
        //得到>1的输入宽高比
        mInputAspectRatio = mPreviewWidth > mPreviewHeight ?
            (float) mPreviewWidth / mPreviewHeight : (float) mPreviewHeight / mPreviewWidth;
        //返回设置好的宽高
        return new int[] { mPreviewWidth, mPreviewHeight };
    }

    public boolean setFilter(final MagicFilterType type) {
        if (mCamera == null) {
            return false;
        }
        //渲染事件队列，用来改变滤镜
        queueEvent(new Runnable() {
            @Override
            public void run() {
                if (magicFilter != null) {
                    magicFilter.destroy();
                }
                magicFilter = MagicFilterFactory.initFilters(type);
                if (magicFilter != null) {
                    magicFilter.init(getContext().getApplicationContext());
                    magicFilter.onInputSizeChanged(mPreviewWidth, mPreviewHeight);
                    magicFilter.onDisplaySizeChanged(mSurfaceWidth, mSurfaceHeight);
                }
            }
        });
        requestRender();
        return true;
    }

    private void deleteTextures() {
        if (mOESTextureId != OpenGLUtils.NO_TEXTURE) {
            queueEvent(new Runnable() {
                @Override
                public void run() {
                    GLES20.glDeleteTextures(1, new int[]{ mOESTextureId }, 0);
                    mOESTextureId = OpenGLUtils.NO_TEXTURE;
                }
            });
        }
    }

    public void setCameraId(int id) {
        stopTorch();
        mCamId = id;
        setPreviewOrientation(mPreviewOrientation);
    }

    public void setPreviewOrientation(int orientation) {
        mPreviewOrientation = orientation;
        Camera.CameraInfo info = new Camera.CameraInfo();
        Camera.getCameraInfo(mCamId, info);
        if (orientation == Configuration.ORIENTATION_PORTRAIT) {
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                mPreviewRotation = info.orientation % 360;
                mPreviewRotation = (360 - mPreviewRotation) % 360;  // compensate the mirror
            } else {
                mPreviewRotation = (info.orientation + 360) % 360;
            }
        } else if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                mPreviewRotation = (info.orientation + 90) % 360;
                mPreviewRotation = (360 - mPreviewRotation) % 360;  // compensate the mirror
            } else {
                mPreviewRotation = (info.orientation + 270) % 360;
            }
        }
    }

    public int getCameraId() {
        return mCamId;
    }

    /**
     * (从缓存中取出帧数据（带滤镜算法），回调出来)
     */
    public void enableEncoding() {
        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                while (!Thread.interrupted()) {
                    while (!mGLIntBufferCache.isEmpty()) {
                        IntBuffer picture = mGLIntBufferCache.poll();
                        mGLPreviewBuffer.asIntBuffer().put(picture.array());//复制一份数据传出去
                        mPrevCb.onGetRgbaFrame(mGLPreviewBuffer.array(), mPreviewWidth, mPreviewHeight);
                    }
                    // Waiting for next frame
                    synchronized (writeLock) {
                        try {
                            // isEmpty() may take some time, so we set timeout to detect next frame
                            writeLock.wait(500);
                        } catch (InterruptedException ie) {
                            worker.interrupt();
                        }
                    }
                }
            }
        });
        worker.start();
        mIsEncoding = true;
    }

    public void disableEncoding() {
        mIsEncoding = false;
        mGLIntBufferCache.clear();

        if (worker != null) {
            worker.interrupt();
            try {
                worker.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
                worker.interrupt();
            }
            worker = null;
        }
    }

    public boolean startCamera() {
        if (mCamera == null) {
            mCamera = openCamera();
            if (mCamera == null) {
                return false;
            }
        }

        Camera.Parameters params = mCamera.getParameters();
        params.setPictureSize(mPreviewWidth, mPreviewHeight);
        params.setPreviewSize(mPreviewWidth, mPreviewHeight);
        int[] range = adaptFpsRange(SrsEncoder.VFPS, params.getSupportedPreviewFpsRange());
        params.setPreviewFpsRange(range[0], range[1]);//设置合适的帧率范围
        params.setPreviewFormat(ImageFormat.NV21);
        params.setFlashMode(Camera.Parameters.FLASH_MODE_OFF);
        params.setWhiteBalance(Camera.Parameters.WHITE_BALANCE_AUTO);
        params.setSceneMode(Camera.Parameters.SCENE_MODE_AUTO);

        List<String> supportedFocusModes = params.getSupportedFocusModes();
        //设置对焦模式，优先持续对焦，次选自动对焦
        if (supportedFocusModes != null && !supportedFocusModes.isEmpty()) {
            if (supportedFocusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
            } else if (supportedFocusModes.contains(Camera.Parameters.FOCUS_MODE_AUTO)) {
                params.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
                mCamera.autoFocus(null);
            } else {
                params.setFocusMode(supportedFocusModes.get(0));
            }
        }
        /*
        闪光灯模式：
            Camera.Parameters.FLASH_MODE_ON 拍照时打开闪光灯
            Camera.Parameters.FLASH_MODE_OFF 拍照时始终关闭闪光灯
            Camera.Parameters.FLASH_MODE_AUTO 系统决定是否开启闪光灯（推荐使用）
            Camera.Parameters.FLASH_MODE_TORCH 手电筒模式 一直开着闪光灯
         */
        List<String> supportedFlashModes = params.getSupportedFlashModes();
        if (supportedFlashModes != null && !supportedFlashModes.isEmpty()) {
            if (supportedFlashModes.contains(Camera.Parameters.FLASH_MODE_TORCH)) {
                if (mIsTorchOn) {
                    params.setFlashMode(Camera.Parameters.FLASH_MODE_TORCH);
                }
            } else {
                params.setFlashMode(supportedFlashModes.get(0));
            }
        }

        mCamera.setParameters(params);

        mCamera.setDisplayOrientation(mPreviewRotation);//相机默认时横屏拍照

        try {
            //绑定纹理
            mCamera.setPreviewTexture(surfaceTexture);
        } catch (IOException e) {
            e.printStackTrace();
        }
        mCamera.startPreview();

        return true;
    }

    /**
     * 关闭相机，关闭帧数据分发线程，释放资源
     */
    public void stopCamera() {
        disableEncoding();

        stopTorch();
        if (mCamera != null) {
            mCamera.stopPreview();
            mCamera.release();
            mCamera = null;
        }
    }

    private Camera openCamera() {
        Camera camera;
        if (mCamId < 0) {
            Camera.CameraInfo info = new Camera.CameraInfo();
            int numCameras = Camera.getNumberOfCameras();
            int frontCamId = -1;
            int backCamId = -1;
            for (int i = 0; i < numCameras; i++) {
                Camera.getCameraInfo(i, info);
                if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) {
                    backCamId = i;
                } else if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                    frontCamId = i;  //只要找到前置摄像头，就执行下一步
                    break;
                }
            }
            //优先找前置摄像头，找不到找后置的，都找不到提供默认值0
            if (frontCamId != -1) {
                mCamId = frontCamId;
            } else if (backCamId != -1) {
                mCamId = backCamId;
            } else {
                mCamId = 0;
            }
        }
        //开启相应的摄像头,并返回实例
        camera = Camera.open(mCamId);
        return camera;
    }

    /**
     * 根据给定的分辨率从设备支持的所有分辨率中找，匹配相同或差距最小（宽高比最接近）的那个分辨率
     * @param resolution
     * @return
     */
    private Camera.Size adaptPreviewResolution(Camera.Size resolution) {
        float diff = 100f;
        float xdy = (float) resolution.width / (float) resolution.height;
        Camera.Size best = null;
        for (Camera.Size size : mCamera.getParameters().getSupportedPreviewSizes()) {
            if (size.equals(resolution)) {
                return size;
            }
            float tmp = Math.abs(((float) size.width / (float) size.height) - xdy);
            if (tmp < diff) {
                diff = tmp;
                best = size;
            }
        }
        return best;
    }

    /**
     * 根据制定帧率，找到最小的支持帧率范围
     * @param expectedFps
     * @param fpsRanges
     * @return
     */
    private int[] adaptFpsRange(int expectedFps, List<int[]> fpsRanges) {
        expectedFps *= 1000;
        int[] closestRange = fpsRanges.get(0);
        int measure = Math.abs(closestRange[0] - expectedFps) + Math.abs(closestRange[1] - expectedFps);
        for (int[] range : fpsRanges) {
            if (range[0] <= expectedFps && range[1] >= expectedFps) {
                int curMeasure = Math.abs(range[0] - expectedFps) + Math.abs(range[1] - expectedFps);
                if (curMeasure < measure) {
                    closestRange = range;
                    measure = curMeasure;
                }
            }
        }
        return closestRange;
    }

    /**
     * 开启手电筒（闪光灯常量）
     * @return
     */
    public boolean startTorch() {
        if (mCamera != null) {
            Camera.Parameters params = mCamera.getParameters();
            List<String> supportedFlashModes = params.getSupportedFlashModes();
            if (supportedFlashModes != null && !supportedFlashModes.isEmpty()) {
                if (supportedFlashModes.contains(Camera.Parameters.FLASH_MODE_TORCH)) {
                    params.setFlashMode(Camera.Parameters.FLASH_MODE_TORCH);
                    mCamera.setParameters(params);
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 关闭手电筒
     */
    public void stopTorch() {
        if (mCamera != null) {
            Camera.Parameters params = mCamera.getParameters();
            params.setFlashMode(Camera.Parameters.FLASH_MODE_OFF);
            mCamera.setParameters(params);
        }
    }

    public interface PreviewCallback {

        void onGetRgbaFrame(byte[] data, int width, int height);
    }
}
