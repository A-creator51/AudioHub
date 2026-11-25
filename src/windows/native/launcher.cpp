#define NOMINMAX
#ifndef UNICODE
#define UNICODE
#endif
#define _UNICODE
#include <windows.h>
#include <shlwapi.h>
#include <jni.h>
#include <WebView2.h>
#include <string>
#include <atomic>
#include <cstring>
#include <fstream>
__CRT_UUID_DECL(ICoreWebView2NavigationStartingEventHandler,0x9adbe429,0xf36d,0x432b,0x9d,0xdc,0xf8,0x88,0x1f,0xbd,0x76,0xe3)
__CRT_UUID_DECL(ICoreWebView2CreateCoreWebView2ControllerCompletedHandler,0x6c4819f3,0xc9b7,0x4260,0x81,0x27,0xc9,0xf5,0xbd,0xe7,0xf6,0x8c)
__CRT_UUID_DECL(ICoreWebView2CreateCoreWebView2EnvironmentCompletedHandler,0x4e8a3389,0xc9d8,0x4bd2,0xb6,0xb5,0x12,0x4f,0xee,0x6c,0xc1,0x4d)
__CRT_UUID_DECL(ICoreWebView2AcceleratorKeyPressedEventHandler,0xb29c7e28,0xfa79,0x41a8,0x8e,0x44,0x65,0x81,0x1c,0x76,0xdc,0xb2)
__CRT_UUID_DECL(ICoreWebView2WebMessageReceivedEventHandler,0x57213f19,0x00e6,0x49fa,0x8e,0x07,0x89,0x8e,0xa0,0x1e,0xcb,0xd2)

namespace {
HWND window;
JavaVM* vm=nullptr;
JNIEnv* env=nullptr;
jclass desktop=nullptr;
ICoreWebView2Controller* controller=nullptr;
ICoreWebView2* view=nullptr;
std::wstring url;
std::wstring logPath;
bool smoke=false;
bool fullscreen=false;
WINDOWPLACEMENT placement{};
LONG_PTR savedStyle=0;
void toggleFullscreen(bool enable){if(fullscreen==enable)return;if(enable){savedStyle=GetWindowLongPtrW(window,GWL_STYLE);placement.length=sizeof(placement);GetWindowPlacement(window,&placement);MONITORINFO info{};info.cbSize=sizeof(info);GetMonitorInfoW(MonitorFromWindow(window,MONITOR_DEFAULTTONEAREST),&info);SetWindowLongPtrW(window,GWL_STYLE,savedStyle&~WS_OVERLAPPEDWINDOW);SetWindowPos(window,HWND_TOP,info.rcMonitor.left,info.rcMonitor.top,info.rcMonitor.right-info.rcMonitor.left,info.rcMonitor.bottom-info.rcMonitor.top,SWP_NOOWNERZORDER|SWP_FRAMECHANGED);}else{SetWindowLongPtrW(window,GWL_STYLE,savedStyle);SetWindowPlacement(window,&placement);SetWindowPos(window,nullptr,0,0,0,0,SWP_NOMOVE|SWP_NOSIZE|SWP_NOZORDER|SWP_NOOWNERZORDER|SWP_FRAMECHANGED);}fullscreen=enable;}
void trace(const char* phase){HANDLE file=CreateFileW(logPath.c_str(),FILE_APPEND_DATA,FILE_SHARE_READ|FILE_SHARE_WRITE,nullptr,OPEN_ALWAYS,FILE_ATTRIBUTE_NORMAL,nullptr);if(file!=INVALID_HANDLE_VALUE){DWORD written;WriteFile(file,phase,DWORD(std::strlen(phase)),&written,nullptr);WriteFile(file,"\r\n",2,&written,nullptr);CloseHandle(file);}}
template<class Interface,class... Args> class Handler final:public Interface {
    std::atomic<ULONG> refs{1};
    HRESULT (*callback)(Args...);
public:
    explicit Handler(HRESULT(*f)(Args...)):callback(f){}
    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID riid,void** out) override {if(riid==IID_IUnknown||riid==__uuidof(Interface)){*out=static_cast<Interface*>(this);AddRef();return S_OK;}*out=nullptr;return E_NOINTERFACE;}
    ULONG STDMETHODCALLTYPE AddRef() override{return ++refs;}
    ULONG STDMETHODCALLTYPE Release() override{auto n=--refs;if(!n)delete this;return n;}
    HRESULT STDMETHODCALLTYPE Invoke(Args... args) override{return callback(args...);}
};
jstring jstr(const std::wstring& text){return env->NewString(reinterpret_cast<const jchar*>(text.data()),jsize(text.size()));}
void javaError(){if(env&&env->ExceptionCheck()){env->ExceptionDescribe();env->ExceptionClear();MessageBoxW(window,L"Java 引擎启动失败。请从完整交付目录运行；查看 docs/使用说明.md。",L"AudioHub",MB_ICONERROR);}}
LRESULT CALLBACK proc(HWND w,UINT message,WPARAM a,LPARAM b){switch(message){case WM_KEYDOWN:if(a==VK_F11){toggleFullscreen(!fullscreen);return 0;}if(a==VK_ESCAPE&&fullscreen){toggleFullscreen(false);return 0;}break;case WM_APP+1:toggleFullscreen(!fullscreen);return 0;case WM_APP+2:toggleFullscreen(false);return 0;case WM_TIMER:if(smoke){KillTimer(w,90);DestroyWindow(w);}return 0;case WM_SIZE:if(controller){RECT r;GetClientRect(w,&r);controller->put_Bounds(r);}return 0;case WM_CLOSE:DestroyWindow(w);return 0;case WM_DESTROY:if(controller)controller->Close();if(desktop&&env){auto stop=env->GetStaticMethodID(desktop,"shutdown","()V");env->CallStaticVoidMethod(desktop,stop);javaError();}trace("host shutdown");PostQuitMessage(0);return 0;}return DefWindowProcW(w,message,a,b);}
HRESULT accelerator(ICoreWebView2Controller*,ICoreWebView2AcceleratorKeyPressedEventArgs* a){COREWEBVIEW2_KEY_EVENT_KIND kind;UINT key=0;a->get_KeyEventKind(&kind);a->get_VirtualKey(&key);if(kind==COREWEBVIEW2_KEY_EVENT_KIND_KEY_DOWN){if(key==VK_F11||(key==VK_ESCAPE&&fullscreen)){a->put_Handled(TRUE);PostMessageW(window,key==VK_F11?WM_APP+1:WM_APP+2,0,0);}}return S_OK;}
HRESULT webMessage(ICoreWebView2*,ICoreWebView2WebMessageReceivedEventArgs* a){LPWSTR source=nullptr,value=nullptr;a->get_Source(&source);a->TryGetWebMessageAsString(&value);if(source&&value&&url==source&&std::wstring(value)==L"fullscreen")PostMessageW(window,WM_APP+1,0,0);CoTaskMemFree(source);CoTaskMemFree(value);return S_OK;}
HRESULT navigation(ICoreWebView2*,ICoreWebView2NavigationStartingEventArgs* args){LPWSTR target=nullptr;args->get_Uri(&target);if(target){std::wstring uri(target);auto slash=url.find_last_of('/');if(uri.compare(0,slash+1,url,0,slash+1)!=0)args->put_Cancel(TRUE);CoTaskMemFree(target);}return S_OK;}
HRESULT createdController(HRESULT hr,ICoreWebView2Controller* result){if(FAILED(hr)||!result){trace("webview controller FAILED");MessageBoxW(window,L"无法创建 WebView2 界面。",L"AudioHub",MB_ICONERROR);return hr;}trace("webview controller ready");controller=result;controller->AddRef();controller->get_CoreWebView2(&view);ICoreWebView2Settings* settings=nullptr;view->get_Settings(&settings);if(settings){settings->put_AreDefaultContextMenusEnabled(FALSE);settings->put_AreDevToolsEnabled(FALSE);settings->put_IsStatusBarEnabled(FALSE);settings->Release();}
    auto h=new Handler<ICoreWebView2NavigationStartingEventHandler,ICoreWebView2*,ICoreWebView2NavigationStartingEventArgs*>(navigation);EventRegistrationToken token;view->add_NavigationStarting(h,&token);h->Release();auto keys=new Handler<ICoreWebView2AcceleratorKeyPressedEventHandler,ICoreWebView2Controller*,ICoreWebView2AcceleratorKeyPressedEventArgs*>(accelerator);controller->add_AcceleratorKeyPressed(keys,&token);keys->Release();auto messages=new Handler<ICoreWebView2WebMessageReceivedEventHandler,ICoreWebView2*,ICoreWebView2WebMessageReceivedEventArgs*>(webMessage);view->add_WebMessageReceived(messages,&token);messages->Release();RECT r;GetClientRect(window,&r);controller->put_Bounds(r);view->Navigate(url.c_str());return S_OK;}
HRESULT createdEnvironment(HRESULT hr,ICoreWebView2Environment* environment){if(FAILED(hr)||!environment){trace("webview environment FAILED");MessageBoxW(window,L"请安装 Microsoft Edge WebView2 Runtime 后重试。",L"AudioHub",MB_ICONERROR);return hr;}trace("webview environment ready");auto h=new Handler<ICoreWebView2CreateCoreWebView2ControllerCompletedHandler,HRESULT,ICoreWebView2Controller*>(createdController);hr=environment->CreateCoreWebView2Controller(window,h);h->Release();return hr;}
}
int WINAPI wWinMain(HINSTANCE instance,HINSTANCE,LPWSTR command,int show){
    smoke=std::wstring(command).find(L"--ui-smoke")!=std::wstring::npos;
    SetProcessDPIAware();CoInitializeEx(nullptr,COINIT_APARTMENTTHREADED);
    wchar_t path[32768];GetModuleFileNameW(nullptr,path,32768);PathRemoveFileSpecW(path);std::wstring root=path;
    logPath=root+L"\\startup.log";trace("launcher start");
    wchar_t javaHome[32768]{};DWORD javaLength=GetEnvironmentVariableW(L"JAVA_HOME",javaHome,32768);
    if(!javaLength||javaLength>=32768){MessageBoxW(nullptr,L"Install 64-bit Java 21 and set JAVA_HOME. This public package does not bundle Java.",L"AudioHub",MB_ICONERROR);return 1;}
    std::wstring javaRoot=javaHome,jvmPath=javaRoot+L"\\bin\\server\\jvm.dll";
    SetDllDirectoryW((javaRoot+L"\\bin").c_str());auto jvm=LoadLibraryW(jvmPath.c_str());
    if(!jvm){trace("jvm load failed");MessageBoxW(nullptr,L"Cannot load Java. JAVA_HOME must point to a 64-bit Java 21 installation.",L"AudioHub",MB_ICONERROR);return 1;}trace("jvm loaded");
    using Create= jint(JNICALL*)(JavaVM**,void**,void*);Create create=nullptr;auto createProc=GetProcAddress(jvm,"JNI_CreateJavaVM");std::memcpy(&create,&createProc,sizeof(create));
    SetCurrentDirectoryW(root.c_str());
    // The JVM option parser on Windows uses the active code page, not UTF-8 paths.
    // An ASCII relative classpath avoids corruption when the installation path is Chinese.
    std::string classpath="-Djava.class.path=engine.jar";JavaVMOption option{classpath.data(),nullptr};JavaVMInitArgs args{};args.version=JNI_VERSION_1_8;args.nOptions=1;args.options=&option;
    if(!create||create(&vm,reinterpret_cast<void**>(&env),&args)!=JNI_OK){trace("jvm create failed");return 2;}trace("jvm created");
    desktop=env->FindClass("org/audiohub/desktop/Desktop");if(!desktop){trace("desktop class failed");javaError();return 3;}trace("desktop class loaded");
    auto open=env->GetStaticMethodID(desktop,"open","(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");auto rootString=jstr(root),dllString=jstr(root+L"\\audiohub_native.dll");
    auto address=static_cast<jstring>(env->CallStaticObjectMethod(desktop,open,rootString,dllString));if(env->ExceptionCheck()||!address){trace("desktop open failed");javaError();return 4;}trace("desktop engine opened");auto chars=env->GetStringChars(address,nullptr);url.assign(reinterpret_cast<const wchar_t*>(chars),env->GetStringLength(address));env->ReleaseStringChars(address,chars);
    WNDCLASSW cls{};cls.lpfnWndProc=proc;cls.hInstance=instance;cls.lpszClassName=L"AudioHub09";cls.hCursor=LoadCursorW(nullptr,IDC_ARROW);cls.hbrBackground=CreateSolidBrush(RGB(24,38,49));RegisterClassW(&cls);
    window=CreateWindowW(cls.lpszClassName,L"AudioHub 0.9.6 · 音频组网 · F11 全屏",WS_OVERLAPPEDWINDOW|WS_CLIPCHILDREN,CW_USEDEFAULT,CW_USEDEFAULT,1120,860,nullptr,nullptr,instance,nullptr);trace(window?"host window created":"host window FAILED");ShowWindow(window,show);if(smoke)SetTimer(window,90,12000,nullptr);
    auto loader=LoadLibraryW((root+L"\\WebView2Loader.dll").c_str());using CreateWeb=HRESULT(STDMETHODCALLTYPE*)(PCWSTR,PCWSTR,ICoreWebView2EnvironmentOptions*,ICoreWebView2CreateCoreWebView2EnvironmentCompletedHandler*);
    CreateWeb web=nullptr;auto webProc=loader?GetProcAddress(loader,"CreateCoreWebView2EnvironmentWithOptions"):nullptr;std::memcpy(&web,&webProc,sizeof(web));
    wchar_t appdata[32768];GetEnvironmentVariableW(L"LOCALAPPDATA",appdata,32768);std::wstring profile=std::wstring(appdata)+L"\\AudioHub-v0.9\\WebView2";
    if(!web){MessageBoxW(window,L"缺少 WebView2Loader.dll。",L"AudioHub",MB_ICONERROR);DestroyWindow(window);}else{auto callback=new Handler<ICoreWebView2CreateCoreWebView2EnvironmentCompletedHandler,HRESULT,ICoreWebView2Environment*>(createdEnvironment);HRESULT result=web(nullptr,profile.c_str(),nullptr,callback);callback->Release();if(FAILED(result)){MessageBoxW(window,L"WebView2 启动失败，请确认运行时可用。",L"AudioHub",MB_ICONERROR);DestroyWindow(window);}}
    MSG message;while(GetMessageW(&message,nullptr,0,0)>0){TranslateMessage(&message);DispatchMessageW(&message);}if(view)view->Release();if(controller)controller->Release();if(vm)vm->DestroyJavaVM();CoUninitialize();return 0;
}
