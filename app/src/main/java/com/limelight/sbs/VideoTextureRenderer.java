/*
By Ahmed Hilali

A derivative work based on: http://github.com/izacus/AndroidOpenGLVideoDemo/
See LICENSE.txt
 */

package com.limelight.sbs;

import android.content.Context;
import android.graphics.*;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;

public class VideoTextureRenderer extends TextureSurfaceRenderer implements SurfaceTexture.OnFrameAvailableListener
{
    private static final String vertexShaderCode =
            "attribute vec4 vPosition;" +
                    "attribute vec4 vTexCoordinate;" +
                    "uniform mat4 textureTransform;" +
                    "varying vec2 v_TexCoordinate;" +
                    "void main() {" +
                    "   v_TexCoordinate = (textureTransform * vTexCoordinate).xy;" +
                    "   gl_Position = vPosition;" +
                    "}";

    private static final String fragmentShaderCode =
            "#extension GL_OES_EGL_image_external : require\n" +
                    "precision highp float;" +
                    "uniform samplerExternalOES texture;" +
                    "uniform float zoomFactor;" +
                    "uniform float distFactor;" +
                    "uniform float wrapEnabled;" +
                    "uniform float singleView;" +
                    "varying vec2 v_TexCoordinate;"
                    + " 		vec2 Warp(vec2 Tex)"
                    + " 		{ "
                    + " 		  vec2 newPos = Tex;"
                    + " 		  float c = -distFactor/10.0;"
                    + " 		  float zoomU = zoomFactor * 0.75;"
                    + " 		  float u = Tex.x*zoomU - (zoomU / 2.0);"
                    + " 		  float v = Tex.y*zoomFactor - (zoomFactor / 2.0);"
                    + " 		  newPos.x = c*u/(pow(v, 2.0) + c);"
                    + " 		  newPos.y = c*v/(pow(u, 2.0) + c);"
                    + " 		    newPos.x = (newPos.x + 1.0)*0.5;"
                    + " 		    newPos.y = (newPos.y + 1.0)*0.5;"
                    + " 		  return newPos; "
                    + " 		} "  +

                    "void main () {"
                    + " 			if(singleView < 0.5) {"
                    + " 			    vec2 newPos = v_TexCoordinate; "
                    + " 			    if(newPos.x < 0.5) {"
                    + " 			    	newPos.x = newPos.x * 2.0;"
                    + " 			    } else { "
                    + " 			    	newPos.x = (newPos.x - 0.5) * 2.0;"
                    + " 			    } "
                    + "                 newPos = Warp(newPos);"
                    + "                 vec4 color = texture2D(texture, newPos);"
                    + " 		        if(wrapEnabled < 0.5) {"
                    + "                     vec2 borderStep = step(0.0, newPos) * step(newPos, vec2(1.0, 1.0));"
                    + " 		            color *= borderStep.x * borderStep.y;"
                    + " 		        }"
                    + "                 gl_FragColor = color;"
                    + "             } else {"
                    + "                 float squeezeFactor = (distFactor / 100.0);"
                    + "                 gl_FragColor = texture2D(texture, vec2(v_TexCoordinate.x, v_TexCoordinate.y * squeezeFactor - (squeezeFactor * 0.5)));"
                    + "             }"
                    + "}";

    private static float squareSize = 1.0f;
    private static float squareCoords[] = { -squareSize,  squareSize, 0.0f,   // top left
            -squareSize, -squareSize, 0.0f,   // bottom left
            squareSize, -squareSize, 0.0f,   // bottom right
            squareSize,  squareSize, 0.0f }; // top right

    private static short drawOrder[] = { 0, 1, 2, 0, 2, 3};

    private Context ctx;

    // Texture to be shown in backgrund
    private FloatBuffer textureBuffer;
    private float textureCoords[] = { 0.0f, 1.0f, 0.0f, 1.0f,
            0.0f, 0.0f, 0.0f, 1.0f,
            1.0f, 0.0f, 0.0f, 1.0f,
            1.0f, 1.0f, 0.0f, 1.0f };
    private int[] textures = new int[1];

    private int vertexShaderHandle;
    private int fragmentShaderHandle;
    private int shaderProgram;
    private FloatBuffer vertexBuffer;
    private ShortBuffer drawListBuffer;

    private float zoomFactor = 3.2f;
    private float distortionFactor = 81.0f;
    private float wrapEnabled = 1.0f;
    private float singleView = 0.0f;
    private boolean zoomedIn = false;

    private SurfaceTexture videoTexture;
    private float[] videoTextureTransform;
    private boolean frameAvailable = false;

    private int videoWidth;
    private int videoHeight;
    private boolean adjustViewport = false;

    public VideoTextureRenderer(Context context, SurfaceTexture texture, int width, int height, OnGlReadyListener listener)
    {
        super(texture, width, height, listener);
        this.ctx = context;
        videoTextureTransform = new float[16];
    }


    private void loadShaders()
    {
        vertexShaderHandle = GLES20.glCreateShader(GLES20.GL_VERTEX_SHADER);
        GLES20.glShaderSource(vertexShaderHandle, vertexShaderCode);
        GLES20.glCompileShader(vertexShaderHandle);
        checkGlError("Vertex shader compile");

        fragmentShaderHandle = GLES20.glCreateShader(GLES20.GL_FRAGMENT_SHADER);
        GLES20.glShaderSource(fragmentShaderHandle, fragmentShaderCode);
        GLES20.glCompileShader(fragmentShaderHandle);
        checkGlError("Pixel shader compile");

        shaderProgram = GLES20.glCreateProgram();
        GLES20.glAttachShader(shaderProgram, vertexShaderHandle);
        GLES20.glAttachShader(shaderProgram, fragmentShaderHandle);
        GLES20.glLinkProgram(shaderProgram);
        checkGlError("Shader program compile");

        int[] status = new int[1];
        GLES20.glGetProgramiv(shaderProgram, GLES20.GL_LINK_STATUS, status, 0);
        if (status[0] != GLES20.GL_TRUE) {
            String error = GLES20.glGetProgramInfoLog(shaderProgram);
            Log.e("SurfaceTest", "Error while linking program:\n" + error);
        }

    }

    public boolean isZoomedIn() {
        return zoomedIn;
    }

    public void setZoomedIn(boolean zoomedIn1) {
        this.zoomedIn = zoomedIn1;
    }

    private void setupVertexBuffer()
    {
        // Draw list buffer
        ByteBuffer dlb = ByteBuffer.allocateDirect(drawOrder. length * 2);
        dlb.order(ByteOrder.nativeOrder());
        drawListBuffer = dlb.asShortBuffer();
        drawListBuffer.put(drawOrder);
        drawListBuffer.position(0);

        // Initialize the texture holder
        ByteBuffer bb = ByteBuffer.allocateDirect(squareCoords.length * 4);
        bb.order(ByteOrder.nativeOrder());

        vertexBuffer = bb.asFloatBuffer();
        vertexBuffer.put(squareCoords);
        vertexBuffer.position(0);
    }


    private void setupTexture(Context context)
    {
        ByteBuffer texturebb = ByteBuffer.allocateDirect(textureCoords.length * 4);
        texturebb.order(ByteOrder.nativeOrder());

        textureBuffer = texturebb.asFloatBuffer();
        textureBuffer.put(textureCoords);
        textureBuffer.position(0);

        // Generate the actual texture
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glGenTextures(1, textures, 0);
        checkGlError("Texture generate");

        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textures[0]);
        checkGlError("Texture bind");

        videoTexture = new SurfaceTexture(textures[0]);
        videoTexture.setOnFrameAvailableListener(this);
    }

    @Override
    protected boolean draw()
    {
        synchronized (this)
        {
            if (frameAvailable)
            {
                videoTexture.updateTexImage();
                videoTexture.getTransformMatrix(videoTextureTransform);
                frameAvailable = false;
            }
            else
            {
                return false;
            }

        }

        if (adjustViewport)
            adjustViewport();

        GLES20.glClearColor(1.0f, 0.0f, 0.0f, 0.0f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        // Draw texture
        GLES20.glUseProgram(shaderProgram);
        int textureParamHandle = GLES20.glGetUniformLocation(shaderProgram, "texture");
        int textureCoordinateHandle = GLES20.glGetAttribLocation(shaderProgram, "vTexCoordinate");
        int positionHandle = GLES20.glGetAttribLocation(shaderProgram, "vPosition");
        int textureTranformHandle = GLES20.glGetUniformLocation(shaderProgram, "textureTransform");
        int zoomHandle = GLES20.glGetUniformLocation(shaderProgram, "zoomFactor");
        int distHandle = GLES20.glGetUniformLocation(shaderProgram, "distFactor");
        int wrapHandle = GLES20.glGetUniformLocation(shaderProgram, "wrapEnabled");
        int singleHandle = GLES20.glGetUniformLocation(shaderProgram, "singleView");

        GLES20.glEnableVertexAttribArray(positionHandle);
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 4 * 3, vertexBuffer);

        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textures[0]);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glUniform1i(textureParamHandle, 0);

        GLES20.glEnableVertexAttribArray(textureCoordinateHandle);
        GLES20.glVertexAttribPointer(textureCoordinateHandle, 4, GLES20.GL_FLOAT, false, 0, textureBuffer);

        GLES20.glUniformMatrix4fv(textureTranformHandle, 1, false, videoTextureTransform, 0);

        float realZoomFactor = zoomedIn ? (zoomFactor * 1.8f) : zoomFactor;
        GLES20.glUniform1f(zoomHandle, realZoomFactor);
        GLES20.glUniform1f(distHandle, distortionFactor);
        GLES20.glUniform1f(wrapHandle, wrapEnabled);
        GLES20.glUniform1f(singleHandle, singleView);

        GLES20.glDrawElements(GLES20.GL_TRIANGLES, drawOrder.length, GLES20.GL_UNSIGNED_SHORT, drawListBuffer);
        GLES20.glDisableVertexAttribArray(positionHandle);
        GLES20.glDisableVertexAttribArray(textureCoordinateHandle);

        return true;
    }

    public void setZoomFactor(float zoomFactor1) {
        this.zoomFactor = zoomFactor1 / 15.625f;
    }

    public void setDistortionFactor(float distortionFactor1) {
        this.distortionFactor = distortionFactor1;
    }

    public void setWrapEnabled(boolean enabled) {
        this.wrapEnabled = enabled ? 1.0f : 0.0f;
    }

    public void setSingleView(boolean enabled) {
        this.singleView = enabled ? 1.0f : 0.0f;
    }

    private void adjustViewport()
    {
        float surfaceAspect = height / (float)width;
        float videoAspect = videoHeight / (float)videoWidth;

        if (surfaceAspect > videoAspect)
        {
            float heightRatio = height / (float)videoHeight;
            int newWidth = (int)(videoWidth * heightRatio);
            int xOffset = (newWidth - width) / 2;
            GLES20.glViewport(-xOffset, 0, newWidth, height);
        }
        else
        {
            float widthRatio = width / (float)videoWidth;
            int newHeight = (int)(videoHeight * widthRatio);
            int yOffset = (newHeight - height) / 2;
            GLES20.glViewport(0, -yOffset, width, newHeight);
        }

        adjustViewport = false;
    }

    @Override
    protected void initGLComponents()
    {
        setupVertexBuffer();
        setupTexture(ctx);
        loadShaders();
    }

    @Override
    protected void deinitGLComponents()
    {
        GLES20.glDeleteTextures(1, textures, 0);
        GLES20.glDeleteProgram(shaderProgram);
        videoTexture.release();
        videoTexture.setOnFrameAvailableListener(null);
    }

    public void setVideoSize(int width, int height)
    {
        this.videoWidth = width;
        this.videoHeight = height;
        adjustViewport = true;
    }

    public void checkGlError(String op)
    {
        int error;
        while ((error = GLES20.glGetError()) != GLES20.GL_NO_ERROR) {
            Log.e("SurfaceTest", op + ": glError " + GLUtils.getEGLErrorString(error));
        }
    }

    public SurfaceTexture getVideoTexture()
    {
        return videoTexture;
    }

    @Override
    public void onFrameAvailable(SurfaceTexture surfaceTexture)
    {
        synchronized (this)
        {
            frameAvailable = true;
        }
    }
}