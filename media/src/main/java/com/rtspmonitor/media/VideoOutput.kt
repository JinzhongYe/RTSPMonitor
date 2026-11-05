package com.rtspmonitor.media

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.opengl.*
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch

/** Decoder output survives Activity surfaces; all EGL operations belong to one thread. */
internal class VideoOutput {
 private val thread = HandlerThread("monitor-video").apply { start() }
 private val handler = Handler(thread.looper)
 private lateinit var display: EGLDisplay
 private lateinit var context: EGLContext
 private lateinit var config: EGLConfig
 private lateinit var buffer: EGLSurface
 private var window: EGLSurface? = null
 private lateinit var texture: SurfaceTexture
 lateinit var surface: Surface
  private set
 private var program = 0
 private var textureId = 0
 private var lastSample = 0L
 private var released=false
 @Volatile var onFrame: ((Bitmap, Long) -> Unit)? = null
 private val matrix = FloatArray(16)
 private val pixels = ByteBuffer.allocateDirect(320 * 320 * 4).order(ByteOrder.nativeOrder())
 private val vertices = ByteBuffer.allocateDirect(16*4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
  put(floatArrayOf(-1f,-1f,0f,0f, 1f,-1f,1f,0f, -1f,1f,0f,1f, 1f,1f,1f,1f));position(0)
 }
 init {
  val ready=CountDownLatch(1)
  var failure: Throwable?=null
  handler.post { try { initialize() } catch(e: Throwable) { failure=e } finally { ready.countDown() } }
  ready.await();failure?.let { throw IllegalStateException("Video output initialization failed",it) }
 }
 private fun initialize() {
  display=EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
  check(EGL14.eglInitialize(display,IntArray(2),0,IntArray(2),0))
  val configs=arrayOfNulls<EGLConfig>(1)
  check(EGL14.eglChooseConfig(display,intArrayOf(EGL14.EGL_RENDERABLE_TYPE,EGL14.EGL_OPENGL_ES2_BIT,EGL14.EGL_SURFACE_TYPE,EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,EGL14.EGL_BLUE_SIZE,8,EGL14.EGL_ALPHA_SIZE,8,EGL14.EGL_NONE),0,configs,0,1,IntArray(1),0))
  config=checkNotNull(configs[0])
  context=EGL14.eglCreateContext(display,config,EGL14.EGL_NO_CONTEXT,intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION,2,EGL14.EGL_NONE),0)
  buffer=EGL14.eglCreatePbufferSurface(display,config,intArrayOf(EGL14.EGL_WIDTH,320,EGL14.EGL_HEIGHT,320,EGL14.EGL_NONE),0)
  current(buffer)
  fun shader(type:Int,source:String):Int=GLES20.glCreateShader(type).also { GLES20.glShaderSource(it,source);GLES20.glCompileShader(it);val status=IntArray(1);GLES20.glGetShaderiv(it,GLES20.GL_COMPILE_STATUS,status,0);check(status[0]!=0) }
  val vs=shader(GLES20.GL_VERTEX_SHADER,"attribute vec2 p;attribute vec2 t;uniform mat4 m;varying vec2 uv;void main(){gl_Position=vec4(p,0.,1.);uv=(m*vec4(t,0.,1.)).xy;}")
  val fs=shader(GLES20.GL_FRAGMENT_SHADER,"#extension GL_OES_EGL_image_external : require\nprecision mediump float;uniform samplerExternalOES image;varying vec2 uv;void main(){gl_FragColor=texture2D(image,uv);}")
  program=GLES20.glCreateProgram();GLES20.glAttachShader(program,vs);GLES20.glAttachShader(program,fs);GLES20.glLinkProgram(program)
  val linked=IntArray(1);GLES20.glGetProgramiv(program,GLES20.GL_LINK_STATUS,linked,0);check(linked[0]!=0)
  GLES20.glDeleteShader(vs);GLES20.glDeleteShader(fs)
  val ids=IntArray(1);GLES20.glGenTextures(1,ids,0);textureId=ids[0]
  GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,textureId)
  GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR)
  GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR)
  GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE)
  GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE)
  texture=SurfaceTexture(textureId).apply { setDefaultBufferSize(640,360);setOnFrameAvailableListener({ draw() },handler) }
  surface=Surface(texture)
 }
 private fun current(target:EGLSurface) { check(EGL14.eglMakeCurrent(display,target,target,context)) }
 fun resize(width:Int,height:Int) { if(width>0 && height>0)handler.post { if(!released)texture.setDefaultBufferSize(width,height) } }
 fun show(surface:Surface?) { handler.post {
  if(released)return@post
  current(buffer);window?.let { EGL14.eglDestroySurface(display,it) };window=null
  if(surface!=null && surface.isValid) {
   val next=EGL14.eglCreateWindowSurface(display,config,surface,intArrayOf(EGL14.EGL_NONE),0)
   if(next!=EGL14.EGL_NO_SURFACE) window=next
  }
 } }
 private fun render(w:Int,h:Int) {
  GLES20.glViewport(0,0,w,h);GLES20.glUseProgram(program)
  GLES20.glActiveTexture(GLES20.GL_TEXTURE0);GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,textureId)
  GLES20.glUniform1i(GLES20.glGetUniformLocation(program,"image"),0)
  GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program,"m"),1,false,matrix,0)
  val p=GLES20.glGetAttribLocation(program,"p");val t=GLES20.glGetAttribLocation(program,"t")
  vertices.position(0);GLES20.glEnableVertexAttribArray(p);GLES20.glVertexAttribPointer(p,2,GLES20.GL_FLOAT,false,16,vertices)
  vertices.position(2);GLES20.glEnableVertexAttribArray(t);GLES20.glVertexAttribPointer(t,2,GLES20.GL_FLOAT,false,16,vertices)
  GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4)
 }
 private fun draw() {
  if(released)return
  current(buffer);texture.updateTexImage();texture.getTransformMatrix(matrix)
  val now=android.os.SystemClock.elapsedRealtime()
  onFrame?.let { callback -> if(now-lastSample>=500) {
   lastSample=now;render(320,320);pixels.clear()
   GLES20.glReadPixels(0,0,320,320,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,pixels)
   val raw=Bitmap.createBitmap(320,320,Bitmap.Config.ARGB_8888);pixels.rewind();raw.copyPixelsFromBuffer(pixels)
   val upright=Bitmap.createBitmap(raw,0,0,320,320,Matrix().apply { preScale(1f,-1f) },false)
   if(upright!==raw)raw.recycle();callback(upright,now)
  } }
  window?.let { target ->
   try { current(target)
   val w=IntArray(1);val h=IntArray(1);EGL14.eglQuerySurface(display,target,EGL14.EGL_WIDTH,w,0);EGL14.eglQuerySurface(display,target,EGL14.EGL_HEIGHT,h,0)
   render(w[0],h[0]);check(EGL14.eglSwapBuffers(display,target))
   } catch(e:RuntimeException) { current(buffer);EGL14.eglDestroySurface(display,target);window=null }
  }
 }
 fun release() { onFrame=null;handler.post {
  released=true;texture.setOnFrameAvailableListener(null);current(buffer);surface.release();texture.release();window?.let { EGL14.eglDestroySurface(display,it) }
  GLES20.glDeleteProgram(program);GLES20.glDeleteTextures(1,intArrayOf(textureId),0)
  EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT)
  // The default display is process-wide; other players may still own EGL contexts.
  EGL14.eglDestroySurface(display,buffer);EGL14.eglDestroyContext(display,context);thread.quitSafely()
 } }
}
