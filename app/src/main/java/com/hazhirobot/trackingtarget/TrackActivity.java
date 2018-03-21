package com.hazhirobot.trackingtarget;

import android.graphics.Bitmap;
import android.os.Bundle;
import android.serialport.SerialPort;
import android.support.annotation.Nullable;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.Toast;

import org.opencv.android.BaseLoaderCallback;
import org.opencv.android.CameraBridgeViewBase;
import org.opencv.android.JavaCameraView;
import org.opencv.android.LoaderCallbackInterface;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Rect;
import org.opencv.core.RotatedRect;
import org.opencv.core.Scalar;
import org.opencv.imgproc.Imgproc;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Created by shijiwei on 2018/3/9.
 *
 * @VERSION 1.0
 */

public class TrackActivity extends AppCompatActivity implements CameraBridgeViewBase.CvCameraViewListener2 {

    private static final String TAG = "TrackActivity";

    private Mat mRgba;
    private Mat mGray;
    private Rect mTrackWindow;

    private JavaCameraView mOpenCvCameraView;
    private ObjectTracker objectTracker;

    private ImageView imageView;

    private TargetFrame preTargetFrame = new TargetFrame();

    private SerialPort mSerialPort;
    private SerialPort mSerialPort_mic;
    private BlockingQueue<byte[]> buffer = new LinkedBlockingQueue(100);
    private SerialPortWriter mWriter;
    private SerialPortReader mReader;

    static {
        if (!OpenCVLoader.initDebug()) {
            Log.e(TAG, "打开library失败！");
        }
    }


    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        //防止运行应用时屏幕关闭
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        imageView = (ImageView) findViewById(R.id.iv_first_frame_image);
        mOpenCvCameraView = (JavaCameraView) findViewById(R.id.main_activity_surface_view);
        mOpenCvCameraView.setCvCameraViewListener(this);
        try {
            mSerialPort = new SerialPort(new File("/dev/ttyS2"), 9600, 0);
            mSerialPort_mic = new SerialPort(new File("/dev/ttyS3"), 115200, 0);
            mWriter = new SerialPortWriter(mSerialPort);
            mReader = new SerialPortReader(mSerialPort_mic);
            mWriter.start();
            mReader.start();
        } catch (IOException e) {
            e.printStackTrace();
        }

    }


    @Override
    protected void onResume() {
        super.onResume();
//        OpenCVLoader.initAsync(OpenCVLoader.OPENCV_VERSION_3_0_0, this, mloaderCallback);
        mOpenCvCameraView.enableView();
        mOpenCvCameraView.setCameraIndex(CameraBridgeViewBase.CAMERA_ID_BACK);
        mOpenCvCameraView.setOnTouchListener(new View.OnTouchListener() {
            int xDown, yDown;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                int cols = mRgba.cols();
                int rows = mRgba.rows();
                int xOffset = (mOpenCvCameraView.getWidth() - cols) / 2;
                int yOffset = (mOpenCvCameraView.getHeight() - rows) / 2;

                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        xDown = (int) event.getX() - xOffset;
                        yDown = (int) event.getY() - yOffset;
                        break;
                    case MotionEvent.ACTION_UP:
                        int xUp = (int) event.getX() - xOffset;
                        int yUp = (int) event.getY() - yOffset;

                        // 获取跟踪目标
                        mTrackWindow = new Rect(Math.min(xDown, xUp), Math.min(yDown, yUp), Math.abs(xUp - xDown), Math.abs(yUp - yDown));

                        // 创建跟踪目标
                        Bitmap bitmap = objectTracker.createTrackedObject(mRgba, mTrackWindow);
                        imageView.setImageBitmap(bitmap);

                        Toast.makeText(getApplicationContext(), "已经选中跟踪目标！", Toast.LENGTH_SHORT).show();
                        break;
                    default:
                        break;
                }
                return true;
            }
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mOpenCvCameraView != null) {
            mOpenCvCameraView.disableView();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mOpenCvCameraView != null) {
            mOpenCvCameraView.disableView();
        }

        if (mSerialPort != null) {
            mSerialPort.close();
            mSerialPort = null;
        }
    }


    @Override
    public void onCameraViewStarted(int width, int height) {
        mRgba = new Mat(height, width, CvType.CV_8UC4);
    }

    @Override
    public void onCameraViewStopped() {
        mRgba.release();
        mGray.release();
    }


    @Override
    public Mat onCameraFrame(CameraBridgeViewBase.CvCameraViewFrame inputFrame) {
        mRgba = inputFrame.rgba();
        mGray = inputFrame.gray();

        if (objectTracker == null) {
            objectTracker = new ObjectTracker(mRgba) {
                @Override
                public void onCalcBackProject(final Bitmap prob) {
                    TrackActivity.this.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            imageView.setImageBitmap(prob);
                        }
                    });
                }
            };
        }

        if (null != mTrackWindow) {

            Scalar FACE_RECT_COLOR = new Scalar(255);
            RotatedRect rotatedRect = objectTracker.objectTracking(mRgba);

            Imgproc.ellipse(mRgba, rotatedRect, FACE_RECT_COLOR, 6);

            Rect rect = rotatedRect.boundingRect();
            Imgproc.rectangle(mRgba, rect.tl(), rect.br(), FACE_RECT_COLOR, 3);

            int centerX = (int) ((rect.br().x + rect.tl().x) / 2);
            int centerY = (int) ((rect.br().y + rect.tl().y) / 2);
            int width = (int) Math.min(rect.br().x - rect.tl().x, rect.br().y - rect.tl().y);

            if (preTargetFrame.getWidth() != -1) {

                int middle = (int) mOpenCvCameraView.mFrameSize.width / 2;
                int offset = middle - centerX;
                byte angle = (byte) (30 * Math.abs(offset) / middle);

                try {
                    if (angle >= 5) {
                        if (offset > 0) {
                            // turn left
                            buffer.put(new byte[]{0x55, 0x0, angle});
                        } else {
                            //turn right
                            buffer.put(new byte[]{0x55, 0x1, angle});
                        }
                    }
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }

            }

            preTargetFrame.setCenter(centerX, centerY);
            preTargetFrame.setWidth(width);


        }

        // System.gc();

        return mRgba;
    }


    private BaseLoaderCallback mloaderCallback = new BaseLoaderCallback(this) {

        @Override
        public void onManagerConnected(int status) {
            switch (status) {
                case LoaderCallbackInterface.SUCCESS: {
                    Log.i(TAG, "OpenCV loaded successfully");
                    mOpenCvCameraView.enableView();
                }
                break;
                default: {
                    super.onManagerConnected(status);
                }
                break;
            }
        }

    };


    class SerialPortWriter extends Thread {

        WeakReference<SerialPort> wt;

        public SerialPortWriter(SerialPort serial) {
            this.wt = new WeakReference<SerialPort>(serial);
        }

        @Override
        public void run() {

            SerialPort serialPort = wt.get();
            if (serialPort != null) {
                OutputStream out = serialPort.getOutputStream();
                while (serialPort != null && out != null) {
                    try {
                        byte[] data = buffer.take();
                        out.write(data);
                        out.flush();
                    } catch (IOException e) {
                        e.printStackTrace();
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }

        }
    }

    class SerialPortReader extends Thread {

        WeakReference<SerialPort> wt;

        public SerialPortReader(SerialPort serial) {
            this.wt = new WeakReference<SerialPort>(serial);
        }

        final String SUFFIX = "Enter your CMD: ";

        @Override
        public void run() {
            super.run();

            SerialPort serialPort = wt.get();
            if (serialPort != null) {
                InputStream in = serialPort.getInputStream();
                byte[] buffer = new byte[1024];
                int len;
                while (serialPort != null && in != null) {
                    try {
                        StringBuffer sbuffer = new StringBuffer();
                        while ((len = in.read(buffer)) != -1) {
                            String cmd_part = new String(buffer, 0, len);
                            sbuffer.append(cmd_part);
                            if (sbuffer.toString().endsWith(SUFFIX)){
                                Log.e(TAG, "Serial Port Reader : " + sbuffer.toString());
                            }
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }

            }

        }
    }

}
