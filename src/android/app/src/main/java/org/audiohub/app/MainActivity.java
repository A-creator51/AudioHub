package org.audiohub.app;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.media.projection.*;
import android.net.Uri;
import android.os.*;
import android.webkit.*;
import org.audiohub.room.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Offline assets and allowlisted native bridge. No arbitrary navigation or remote scripts. */
public final class MainActivity extends Activity {
    private WebView web;
    private final ExecutorService work=Executors.newSingleThreadExecutor();
    private Controller controller;
    private boolean pending;
    private long pendingRevision;
    private String pendingSource;
    private ArrayList<String> pendingOutputs;
    private static final int AUDIO_PERMISSION=10,PROJECTION=11;
    private static final int EXPORT_LOG=12;
    private String logExport;
    private String pendingBluetooth;
    @Override public void onCreate(Bundle saved){super.onCreate(saved);AppState.init(this);
        controller=new Controller(AppState.room){@Override protected void start(boolean send,String source,List<String> outputs){
            runOnUiThread(()->requestAudio(send,source,outputs));
        }};
        web=new WebView(this);web.setBackgroundColor(0xff182631);WebSettings settings=web.getSettings();settings.setJavaScriptEnabled(true);settings.setDomStorageEnabled(true);settings.setAllowFileAccess(false);settings.setAllowContentAccess(false);settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);settings.setSupportMultipleWindows(false);
        web.addJavascriptInterface(new Bridge(),"AudioHubNative");
        web.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){return !request.getUrl().toString().startsWith("https://audiohub.invalid/");}
            @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest request){Uri uri=request.getUrl();if(!"audiohub.invalid".equals(uri.getHost()))return new WebResourceResponse("text/plain","UTF-8",new ByteArrayInputStream(new byte[0]));String path=uri.getPath();if(path==null||path.equals("/"))path="/index.html";
                String file=path.substring(1);if(!Arrays.asList("index.html","app.js","style.css").contains(file))return new WebResourceResponse("text/plain","UTF-8",new ByteArrayInputStream(new byte[0]));
                try{return new WebResourceResponse(file.endsWith(".js")?"text/javascript":file.endsWith(".css")?"text/css":"text/html","UTF-8",getAssets().open(file));}catch(IOException e){return new WebResourceResponse("text/plain","UTF-8",new ByteArrayInputStream(new byte[0]));}
            }
        });setContentView(web);web.loadUrl("https://audiohub.invalid/index.html");
    }
    private final class Bridge {
        @JavascriptInterface public String state(){return AppState.room.snapshot();}
        @JavascriptInterface public void command(String form){if(form.length()>16384)return;if(Build.VERSION.SDK_INT>=31&&form.startsWith("action=bt")&&checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED){runOnUiThread(()->{if(pendingBluetooth!=null){AppState.room.report("正在等待附近设备权限");return;}pendingBluetooth=form;requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT},13);});return;}dispatch(form);}
        @JavascriptInterface public void copy(String code){runOnUiThread(()->getSystemService(android.content.ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("AudioHub 房间码",code)));}
        @JavascriptInterface public void exportLogs(String content){if(content.length()>262144)return;runOnUiThread(()->{logExport=content;try{startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("text/plain").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"AudioHub-0.9-log.txt"),EXPORT_LOG);}catch(Exception e){logExport=null;AppState.room.report("无法导出日志："+e.getMessage());}});}
    }
    private void dispatch(String form){work.submit(()->{controller.command(form);boolean connection=form.startsWith("action=host&")||form.startsWith("action=join&")||form.startsWith("action=btHost")||form.startsWith("action=btJoin");if(connection&&!AppState.room.mode().equals("idle"))runOnUiThread(()->{try{startForegroundService(new Intent(MainActivity.this,RoomService.class));}catch(Exception e){AppState.room.leave();AppState.room.report("无法维持房间："+e.getMessage());}});});}
    private void requestAudio(boolean send,String source,List<String> outputs){
        if(pending){AppState.room.report("请先完成媒体捕获授权");return;}
        synchronized(AppState.room){
            if(!AppState.room.mode().equals("host")&&!AppState.room.mode().equals("client")){AppState.room.report("请先创建或加入房间");return;}
            pendingRevision=AppState.room.audioRevision();
            if(!send||AppState.room.sending()){launchAudio(send,source,new ArrayList<>(outputs),null,0,pendingRevision);return;}
            pending=true;AppState.room.authorizing(true);pendingSource=source;pendingOutputs=new ArrayList<>(outputs);
        }
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},AUDIO_PERMISSION);else projection();
    }
    private boolean currentConsent(){synchronized(AppState.room){return pending&&pendingRevision==AppState.room.audioRevision()&&(AppState.room.mode().equals("host")||AppState.room.mode().equals("client"));}}
    private void clearConsent(){synchronized(AppState.room){if(pendingRevision==AppState.room.audioRevision())AppState.room.authorizing(false);}pending=false;pendingSource=null;pendingOutputs=null;}
    private void projection(){if(!currentConsent()){clearConsent();AppState.room.report("房间或音频状态已改变，旧采集授权请求已作废");return;}MediaProjectionManager manager=getSystemService(MediaProjectionManager.class);try{Intent intent=Build.VERSION.SDK_INT>=34?manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay()):manager.createScreenCaptureIntent();startActivityForResult(intent,PROJECTION);}catch(Exception e){clearConsent();AppState.room.report("无法请求媒体授权："+e.getMessage());}}
    @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] results){super.onRequestPermissionsResult(code,permissions,results);if(code==13){String form=pendingBluetooth;pendingBluetooth=null;if(Build.VERSION.SDK_INT>=31&&checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED){if(form!=null)dispatch(form);}else AppState.room.report("附近设备权限被拒绝；未连接、未采集");return;}if(code==AUDIO_PERMISSION){if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED)projection();else{clearConsent();AppState.room.report("未授予媒体捕获权限；不会使用麦克风");}}}
    @Override public void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==EXPORT_LOG){String content=logExport;logExport=null;if(result==RESULT_OK&&data!=null&&data.getData()!=null&&content!=null){work.submit(()->{try(OutputStream out=getContentResolver().openOutputStream(data.getData())){if(out==null)throw new IOException("无法打开文件");out.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));}catch(Exception e){AppState.room.report("日志导出失败："+e.getMessage());}});}return;}if(request!=PROJECTION)return;if(!currentConsent()){clearConsent();AppState.room.report("房间或音频状态已改变，已丢弃旧授权；未启动采集");return;}if(result!=RESULT_OK||data==null){clearConsent();AppState.room.report("媒体捕获授权已取消");return;}launchAudio(true,pendingSource,pendingOutputs,data,result,pendingRevision);pending=false;pendingOutputs=null;pendingSource=null;}
    private void launchAudio(boolean send,String source,ArrayList<String> outputs,Intent data,int result,long revision){try{Intent intent=new Intent(this,RoomService.class).setAction(RoomService.AUDIO).putExtra("send",send).putExtra("source",source).putExtra("audioRevision",revision).putStringArrayListExtra("outputs",outputs);if(data!=null)intent.putExtra("projectionData",data).putExtra("projectionResult",result);startForegroundService(intent);}catch(Exception e){synchronized(AppState.room){if(revision==AppState.room.audioRevision())AppState.room.authorizing(false);}AppState.room.report("音频启动失败："+e.getMessage());}}
    @Override public void onDestroy(){if(pending)clearConsent();work.shutdownNow();if(web!=null){web.removeJavascriptInterface("AudioHubNative");web.destroy();}super.onDestroy();}
}
