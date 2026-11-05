#include <jni.h>
#include <stdatomic.h>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <libavformat/avformat.h>
#include <libavutil/time.h>
#include <libavutil/log.h>
#include <libavutil/mathematics.h>

typedef struct { atomic_int stop; int64_t deadline; } Control;
static int interrupt(void *p) { Control *c=p; return atomic_load(&c->stop) || av_gettime_relative() > c->deadline; }
static void event(JNIEnv *env, jobject cb, const char *kind, const char *value) {
 jclass cls=(*env)->GetObjectClass(env,cb);
 jmethodID method=(*env)->GetMethodID(env,cls,"event","(Ljava/lang/String;Ljava/lang/String;)V");
 jstring k=(*env)->NewStringUTF(env,kind), v=(*env)->NewStringUTF(env,value);
 (*env)->CallVoidMethod(env,cb,method,k,v);
 (*env)->DeleteLocalRef(env,k); (*env)->DeleteLocalRef(env,v); (*env)->DeleteLocalRef(env,cls);
}
JNIEXPORT jlong JNICALL Java_com_rtspmonitor_media_AvRecorder_create(JNIEnv *e,jobject o) { return (jlong)calloc(1,sizeof(Control)); }
JNIEXPORT void JNICALL Java_com_rtspmonitor_media_AvRecorder_cancel(JNIEnv *e,jobject o,jlong h) { if(h) atomic_store(&((Control*)h)->stop,1); }
JNIEXPORT void JNICALL Java_com_rtspmonitor_media_AvRecorder_destroy(JNIEnv *e,jobject o,jlong h) { free((void*)h); }
static int finish(AVFormatContext **out) {
 if(!*out) return 0;
 int r=av_write_trailer(*out), close=avio_closep(&(*out)->pb);
 avformat_free_context(*out); *out=NULL; return r<0?r:close;
}
JNIEXPORT jint JNICALL Java_com_rtspmonitor_media_AvRecorder_run(JNIEnv *env,jobject self,jlong handle,jstring jurl,jboolean tcp,jstring jdir,jint seconds,jobject cb) {
 Control *ctl=(Control*)handle;
 const char *url=(*env)->GetStringUTFChars(env,jurl,NULL), *dir=(*env)->GetStringUTFChars(env,jdir,NULL);
 AVFormatContext *in=avformat_alloc_context(), *out=NULL;
 AVPacket *pkt=av_packet_alloc(); AVDictionary *opts=NULL;
 int result=-1, video=-1, audio=-1, map[2], seq=0, warned=0, output_error=0; int64_t wait_start=av_gettime_relative(); int64_t start=AV_NOPTS_VALUE, last[2]={AV_NOPTS_VALUE,AV_NOPTS_VALUE};
 char path[4096]={0}, final[4096]={0};
 av_log_set_level(AV_LOG_QUIET); // Native diagnostics may contain RTSP credentials.
 ctl->deadline=av_gettime_relative()+15000000;
 in->interrupt_callback=(AVIOInterruptCB){interrupt,ctl};
 av_dict_set(&opts,"rtsp_transport",tcp?"tcp":"udp",0);
 av_dict_set(&opts,"rw_timeout","15000000",0);
 result=avformat_open_input(&in,url,NULL,&opts); av_dict_free(&opts);
 if(result<0) goto done;
 ctl->deadline=av_gettime_relative()+15000000;
 result=avformat_find_stream_info(in,NULL); if(result<0) goto done;
 // Stream metadata alone can be available even when RTP probing timed out.
 if(interrupt(ctl)) { result=atomic_load(&ctl->stop)?AVERROR_EXIT:AVERROR(ETIMEDOUT); goto done; }
 for(unsigned i=0;i<in->nb_streams;i++) {
  AVCodecParameters *p=in->streams[i]->codecpar;
  if(video<0 && p->codec_type==AVMEDIA_TYPE_VIDEO && (p->codec_id==AV_CODEC_ID_H264 || p->codec_id==AV_CODEC_ID_HEVC)) video=i;
  if(audio<0 && p->codec_type==AVMEDIA_TYPE_AUDIO && p->codec_id==AV_CODEC_ID_AAC) audio=i;
  else if(p->codec_type==AVMEDIA_TYPE_AUDIO && p->codec_id!=AV_CODEC_ID_AAC) event(env,cb,"warning","不兼容音频已忽略；视频不转码");
 }
 if(video<0) { result=-415; goto done; }
 map[0]=video; map[1]=audio;
 while(!atomic_load(&ctl->stop)) {
  ctl->deadline=av_gettime_relative()+15000000;
  result=av_read_frame(in,pkt); if(result<0) break;
  int index=pkt->stream_index==video?0:(pkt->stream_index==audio?1:-1);
  size_t extra_size=0;
  if(av_packet_get_side_data(pkt,AV_PKT_DATA_NEW_EXTRADATA,&extra_size) && out) { result=AVERROR_INVALIDDATA; break; }
  if(index<0 || pkt->dts==AV_NOPTS_VALUE || pkt->pts==AV_NOPTS_VALUE) { av_packet_unref(pkt); continue; }
  int64_t dts=av_rescale_q(pkt->dts,in->streams[pkt->stream_index]->time_base,AV_TIME_BASE_Q);
  int key=index==0 && (pkt->flags & AV_PKT_FLAG_KEY);
  if(out && key && dts-start >= (int64_t)seconds*AV_TIME_BASE) {
   result=finish(&out); if(result<0) break;
   if(rename(path,final)!=0) { result=AVERROR(EIO); break; }
   event(env,cb,"segment",final);
  }
  if(!out) {
   if(!key) { if(!warned && av_gettime_relative()-wait_start>30000000) { event(env,cb,"warning","等待视频关键帧超过30秒，请检查摄像头GOP设置"); warned=1; } av_packet_unref(pkt); continue; }
   start=dts; last[0]=last[1]=AV_NOPTS_VALUE;
   snprintf(path,sizeof(path),"%s/%06d.part",dir,seq);
   snprintf(final,sizeof(final),"%s/%06d.mp4",dir,seq++);
   result=avformat_alloc_output_context2(&out,NULL,"mp4",path); if(result<0) goto done;
   // Persist completed fMP4 fragments instead of leaving them in the AVIO buffer.
   out->flags |= AVFMT_FLAG_FLUSH_PACKETS;
   for(int i=0;i<2 && map[i]>=0;i++) {
    AVStream *s=avformat_new_stream(out,NULL); if(!s) { result=AVERROR(ENOMEM); goto done; }
    result=avcodec_parameters_copy(s->codecpar,in->streams[map[i]]->codecpar); if(result<0) goto done;
    s->codecpar->codec_tag=0; s->time_base=in->streams[map[i]]->time_base;
   }
   result=avio_open(&out->pb,path,AVIO_FLAG_WRITE); if(result<0) { output_error=1; goto done; }
   av_dict_set(&opts,"movflags","+frag_keyframe+empty_moov+default_base_moof",0);
   result=avformat_write_header(out,&opts); av_dict_free(&opts); if(result<0) { output_error=1; goto done; }
   event(env,cb,"recording","");
  }
  AVStream *src=in->streams[pkt->stream_index], *dst=out->streams[index];
  int64_t offset=av_rescale_q(start,AV_TIME_BASE_Q,src->time_base);
  pkt->pts-=offset; pkt->dts-=offset;
  if(pkt->dts<0) { av_packet_unref(pkt); continue; }
  av_packet_rescale_ts(pkt,src->time_base,dst->time_base); pkt->stream_index=index; pkt->pos=-1;
  if(last[index]!=AV_NOPTS_VALUE && pkt->dts<=last[index]) { result=AVERROR_INVALIDDATA; break; }
  last[index]=pkt->dts;
  result=av_interleaved_write_frame(out,pkt); av_packet_unref(pkt); if(result<0) { output_error=1; break; }
 }
 if(out) {
  int r=finish(&out);
  if(!output_error && r>=0 && rename(path,final)==0) event(env,cb,"segment",final);
  else if(!output_error) { result=r<0?r:AVERROR(EIO); output_error=1; }
 }
 if(atomic_load(&ctl->stop) && result>=0) result=0;
done:
 if(output_error && (result==AVERROR(ENOSPC) || result==AVERROR(EACCES) || result==AVERROR(EROFS) || result==AVERROR(EIO) || result==AVERROR(ENOENT))) event(env,cb,"storage-error",result==AVERROR(ENOSPC)?"存储空间不足":"录像文件写入失败");
 if(out) { if(out->pb) avio_closep(&out->pb); avformat_free_context(out); }
 av_packet_free(&pkt); avformat_close_input(&in); av_dict_free(&opts);
 (*env)->ReleaseStringUTFChars(env,jurl,url); (*env)->ReleaseStringUTFChars(env,jdir,dir);
 if(result==AVERROR_HTTP_UNAUTHORIZED || result==AVERROR_HTTP_FORBIDDEN) return -401;
 return atomic_load(&ctl->stop) && result==AVERROR_EXIT?0:result;
}
