'use strict';
const $=id=>document.getElementById(id);
const android=typeof AudioHubNative!=='undefined';
let state={},tab='overview',lastDevices='',lastRoom='',lastGains='',lastOutputs='',polling=false;
let range=2,settingsLoaded=false,settingsDirty=false,settingsPending='',outputValues={},devicesRendered=false;
let lastBluetoothDevices='',lastA2dpDevices='',lastCaptureDevices='';
const pendingRanges={};
const settingIds=['outputMs','reserveMs','inputMs','mmcss','lanRange','bluetoothRange','captureDetail','autostart','followLog','logLevel','bluetoothProtocol','bluetoothCodec'];
let preferences={};try{preferences=JSON.parse(localStorage.getItem('preferences')||'{}');}catch(e){}
const savePreferences=()=>localStorage.setItem('preferences',JSON.stringify(preferences));
const text=(tag,value,className)=>{const e=document.createElement(tag);e.textContent=value;if(className)e.className=className;return e;};
async function command(action,values={}){const form=new URLSearchParams({action,...values}).toString();try{if(android)AudioHubNative.command(form);else await fetch('command',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:form});}catch(e){$('error').hidden=false;$('error').textContent='操作失败：'+e.message;}setTimeout(refresh,150);}
document.querySelectorAll('[data-tab]').forEach(button=>button.addEventListener('click',()=>{tab=button.dataset.tab;preferences.tab=tab;savePreferences();document.querySelectorAll('.page').forEach(p=>p.hidden=p.id!==tab);document.querySelectorAll('[data-tab]').forEach(b=>b.classList.toggle('selected',b===button));if(tab==='bluetooth'||tab==='lan'){const target=$(tab==='bluetooth'?'btAudioPanels':'lan');target.append($('localInputPanel'),$('localOutputPanel'));if(devicesRendered)render();}requestAnimationFrame(arrows);}));
if(document.querySelector('[data-tab="'+preferences.tab+'"]'))document.querySelector('[data-tab="'+preferences.tab+'"]').click();
$('address').addEventListener('change',()=>{preferences.address=$('address').value;savePreferences();});
$('port').value=preferences.port||47162;$('port').addEventListener('change',()=>{preferences.port=$('port').value;savePreferences();});
$('send').checked=false;$('send').addEventListener('change',()=>{rememberDevices();command('input',{send:$('send').checked,source:$('source').value});});$('source').addEventListener('change',rememberDevices);
function rememberDevices(){preferences.send=$('send').checked;preferences.source=$('source').value;preferences.outputs=Array.from(document.querySelectorAll('#deviceOutputs input:checked')).map(i=>i.value);savePreferences();}
$('host').addEventListener('click',()=>command('host',{address:$('address').value,port:$('port').value}));
$('leave').addEventListener('click',()=>command('leave'));
$('join').addEventListener('click',()=>{const code=$('joinCode').value;command('join',{code});$('joinCode').value='';});
$('copy').addEventListener('click',async()=>{if(!state.code)return;try{if(android)AudioHubNative.copy(state.code);else await navigator.clipboard.writeText(state.code);}catch(e){$('roomCode').select();document.execCommand('copy');}});
$('start').addEventListener('click',()=>{const outputs=Array.from(document.querySelectorAll('#deviceOutputs input:checked')).map(e=>e.value);command('outputs',{outputs:outputs.join('\n')});});
$('stop').addEventListener('click',()=>command('outputs',{outputs:''}));
$('refreshDevices').addEventListener('click',()=>command('refreshDevices'));
$('fullscreen').hidden=!window.chrome?.webview;$('fullscreen').addEventListener('click',()=>window.chrome?.webview?.postMessage('fullscreen'));
$('btHost').addEventListener('click',()=>command('btHost'));
$('btRefresh').addEventListener('click',()=>command('btRefresh'));
$('btLeave').addEventListener('click',()=>command('leave'));
$('btJoin').addEventListener('click',()=>{command('btJoin',{code:$('btJoinCode').value,device:$('btDevice').value});$('btJoinCode').value='';});
$('btCopy').addEventListener('click',async()=>{if(!state.code?.startsWith('AHB2|'))return;try{if(android)AudioHubNative.copy(state.code);else await navigator.clipboard.writeText(state.code);}catch(e){$('btCode').select();document.execCommand('copy');}});
for(const [id,operation] of [['a2dpRefresh','refresh'],['a2dpConnect','start'],['a2dpDisconnect','stop']])$(id).addEventListener('click',()=>{const activeCustom=state.transport==='bluetooth'&&state.mode!=='idle',activeDirect=state.mode==='a2dp';let confirmMode=false;if((operation==='start'&&activeCustom)||(operation==='stop'&&activeDirect)){if(!confirm(operation==='start'?'开启直接连接将关闭当前蓝牙房间，并停止该会话的采集/播放。是否继续？':'退出直接连接将断开 A2DP，并停止此路径的采集/播放。是否继续？'))return;confirmMode=true;}command('direct',{operation,device:$('a2dpDevice').value,confirmMode});});
for(const [id,operation] of [['directCaptureStart','start'],['directCaptureStop','stop'],['directCaptureRefresh','refresh']])$(id).addEventListener('click',()=>command('directCapture',{operation,source:$('directCaptureDevice').value}));
$('directCaptureDevice').addEventListener('change',()=>{preferences.directCaptureSource=$('directCaptureDevice').value;savePreferences();if(state.mode==='a2dp')$('source').value=$('directCaptureDevice').value;renderDirectCapture();});
$('selfTest').addEventListener('click',()=>command('selfTest'));
$('clearLogs').addEventListener('click',()=>command('clearLogs'));
$('exportLogs').addEventListener('click',()=>{const content='AudioHub 0.9.6 音频链路快照\n连接方式：'+(state.transport||'lan')+'\n'+$('audioDiagnostics').textContent+'\n'+(state.room?.members||[]).map(m=>`${m.name}：输入=${!!m.send}，输出=${(m.outputs||[]).length}，收到=${m.received||0}，非静音=${m.audible||0}，电平=${Math.round((m.peak||0)*100)}%，丢旧帧=${m.drops||0}，欠载=${m.gaps||0}`).join('\n')+'\n\n'+(state.logs||[]).join('\n');if(android){AudioHubNative.exportLogs(content);return;}const a=document.createElement('a');a.href=URL.createObjectURL(new Blob([content],{type:'text/plain;charset=utf-8'}));a.download='AudioHub-0.9.6-log.txt';a.click();setTimeout(()=>URL.revokeObjectURL(a.href),1000);});
function readSettings(){return Object.fromEntries(settingIds.map(id=>[id,$(id).type==='checkbox'?$(id).checked:$(id).value]));}
function fillSettings(s){for(const id of settingIds)if(s[id]!==undefined){if($(id).type==='checkbox')$(id).checked=s[id]===true||s[id]==='true';else $(id).value=String(s[id]);}}
function applyRange(path){const value=$(path+'Range').value;pendingRanges[path]={value,time:Date.now(),error:state.error};$('rangeStatus').textContent='正在实时应用并保存音量范围…';$('saveSettings').disabled=true;command('volumeRange',{path,range:value});}
for(const id of settingIds)$(id).addEventListener('change',()=>{if(id==='lanRange'||id==='bluetoothRange'){applyRange(id==='lanRange'?'lan':'bluetooth');return;}settingsDirty=true;$('settingsStatus').textContent='有未保存的更改。';renderLogs();});
$('saveSettings').addEventListener('click',()=>{const values=readSettings();settingsPending=JSON.stringify(values);command('saveSettings',values);$('settingsStatus').textContent='正在保存设置…';});
$('defaultSettings').addEventListener('click',()=>{fillSettings({outputMs:160,reserveMs:100,inputMs:100,mmcss:true,lanRange:200,bluetoothRange:400,captureDetail:false});applyRange('lan');applyRange('bluetooth');settingsDirty=true;$('bufferPreset').value='custom';$('settingsStatus').textContent='默认音量范围立即应用；其他默认音频参数点击保存生效，登录自启不变。';});
$('bufferPreset').addEventListener('change',()=>{const p={low:[100,40,60],balanced:[160,100,100],stable:[400,200,160]}[$('bufferPreset').value];if(p){fillSettings({outputMs:p[0],reserveMs:p[1],inputMs:p[2]});settingsDirty=true;$('settingsStatus').textContent='预设已填入，点击保存后下次启用本机音频生效。';}});
function renderLogs(){const levels=['DEBUG','INFO','WARN','ERROR','OFF'],threshold=levels.indexOf($('logLevel').value);const lines=(state.logs||[]).filter(line=>{const match=line.match(/\[(DEBUG|INFO|WARN|ERROR)\]/);return threshold!==4&&(!match||levels.indexOf(match[1])>=threshold);});const log=lines.join('\n')||'暂无日志。创建或加入房间后，这里显示连接过程。';if($('logText').textContent!==log){const position=$('logText').scrollTop;$('logText').textContent=log;$('logText').scrollTop=$('followLog').checked?$('logText').scrollHeight:position;}}
function gainRow(label,value,max,enabled,callback){const row=text('div','', 'gain');row.append(text('span',label));const input=document.createElement('input');input.type='range';input.min=0;input.max=max;input.step=1;input.value=Math.min(max,Math.round(value*100));input.disabled=!enabled;input.setAttribute('aria-label',label+'音量');const out=text('output',input.value+'%');input.addEventListener('input',()=>out.textContent=input.value+'%');input.addEventListener('change',()=>callback(Number(input.value)/100));row.append(input,out);return row;}
function syncGainLimits(){document.querySelectorAll('#inputGains input,#outputGains input').forEach(input=>{input.max=range*100;input.nextElementSibling.textContent=input.value+'%';});}
function renderRanges(){if(!state.settings)return;for(const path of ['lan','bluetooth']){const id=path+'Range',pending=pendingRanges[path];if(pending){if(String(state.settings[id])===pending.value){delete pendingRanges[path];$('rangeStatus').textContent='音量范围已实时应用并保存；其他设置草稿保持不变。';}else if(Date.now()-pending.time>5000||(state.error&&state.error!==pending.error)){delete pendingRanges[path];$('rangeStatus').textContent='音量范围未确认保存：'+(state.error||'连接超时，请重试');}}if(!pendingRanges[path])$(id).value=String(state.settings[id]);}const busy=Object.keys(pendingRanges).length>0;$('saveSettings').disabled=busy||!!settingsPending;$('lanRange').disabled=!!settingsPending;$('bluetoothRange').disabled=!!settingsPending;}
function renderDirectCapture(){const cap=state.a2dp||{},active=state.mode==='a2dp',busy=!!state.pendingAudio,select=$('directCaptureDevice'),devices=(state.devices||[]).filter(d=>d.input),key=JSON.stringify(devices),current=state.localSend&&active?state.localSource:null;
 if(key!==lastCaptureDevices||current&&select.value!==current){const old=current||select.value||preferences.directCaptureSource;lastCaptureDevices=key;select.replaceChildren(...devices.map(d=>new Option(d.name,d.id)));if(old){if(!devices.some(d=>d.id===old)){const missing=new Option('原回采设备已移除，请重新选择',old);missing.disabled=true;select.append(missing);}select.value=old;}}
 const valid=devices.some(d=>d.id===select.value);select.disabled=!cap.supported||busy||active&&!!state.localSend;
 $('directCaptureStart').disabled=!cap.supported||!cap.connected||!active||busy||!!state.localSend||!valid;
 $('directCaptureStop').disabled=!cap.supported||!active||busy||!state.localSend;
 $('directCaptureRefresh').disabled=!cap.supported||busy;
 $('directCaptureStatus').textContent=!cap.supported?'此平台不支持直接连接回采。':!active?'先开启直接连接；回采不会自动启动。':state.localSend?'回采已开启；在“本机输出”选择不同播放设备实现多路输出。':cap.connected?'当前未开启回采，可使用 WIN 直出；本机播放与回采分别控制。':'等待直接连接就绪。';
}
function renderAudioFlow(){let message;
    const selected=Array.from(document.querySelectorAll('#deviceOutputs input:checked'));
    if(state.pendingAudio)message='正在等待中心确认或系统授权，请完成授权，不要重复启动。';
    else if(!state.running)message=state.mode==='a2dp'?'软件回采与播放均未开启，可使用 WIN 直出；需要软件多路输出时，在直接连接框开启回采，再选择不同播放设备并启用。':state.mode==='host'||state.mode==='client'?'输入与播放均未启用：勾选“发送本机声音”立即发送；本机播放另选设备并点击“启用本机音频”。':'请先创建或加入房间，输入发送与本机播放分别控制。';
    else if(state.localSend&&!(state.capturePackets>0))message='输入已启用，但尚未收到采集数据；请检查系统媒体捕获授权。';
    else if(state.localSend&&!(state.captureAudible>0))message='正在采集，但目前均为静音。请播放允许捕获的媒体，并检查声音来源；受保护内容可能禁止捕获。';
    else if(!(state.localOutputs||[]).length)message='本机正在发送，不播放；启用/停用本机音频仅控制下方播放设备，不影响发送。';
    else if(!(state.outputPackets>0))message='本机输出已启用，等待中心混音数据；检查输入端是否已启用并有电平。';
    else if(!(state.outputAudible>0))message='已收到混音数据，但目前均为静音；检查中心输入电平及各输入增益。';
    else message='已收到非静音混音并提交播放；若仍无声，请检查下方实际播放路由、软件与系统音量。';
    $('audioFlow').textContent=message;
    $('audioDiagnostics').textContent=`采集：${state.capturePackets||0} 包 / 非静音 ${state.captureAudible||0} 包 / 电平 ${Math.round((state.capturePeak||0)*100)}%\n本机输出提交：${state.outputPackets||0} 包 / 非静音 ${state.outputAudible||0} 包 / 电平 ${Math.round((state.outputPeak||0)*100)}%\n${state.audioStatus||'尚未启用音频'}`;
}
function overviewMembers(members){const result=members.map(m=>({...m,devices:m.devices||[]}));let local=result.find(m=>m.id===state.id);if(!local){local={id:state.id,name:state.name||'本机',send:!!state.localSend,outputs:state.localOutputs||[],source:state.localSource,ready:!!state.running,devices:[]};result.push(local);}local.devices=state.devices||[];local.source=state.localSource??local.source;if(state.running!==undefined)local.ready=state.running;return result;}
function renderBluetooth(idle,bt){
 const a2dp=state.settings?.a2dp===true||state.settings?.a2dp==='true',cap=state.a2dp||{},mode=state.mode;
 $('bluetoothMode').textContent='当前连接方式：'+(a2dp?'直接连接（标准 A2DP）':'软件自定义蓝牙 / RFCOMM · TLS1.2 · IMA1');
 $('a2dpReason').textContent=cap.reason||'正在读取平台能力';
 $('btRoomControls').hidden=false;$('btRoomHint').textContent=a2dp?'直接连接已启用，软件蓝牙房间已禁用；请先关闭/退出直接连接，再创建或加入蓝牙房间。':'中心内部混音后分发；任意受支持设备可创建或加入，中心不必播放。';
 $('btHost').disabled=!idle||a2dp;$('btJoin').disabled=!idle||a2dp;$('btRefresh').disabled=a2dp;$('btLeave').disabled=idle||a2dp||state.transport!=='bluetooth';$('btDevice').disabled=a2dp;$('btJoinCode').disabled=a2dp||!idle;
 $('btCode').value=state.code?.startsWith('AHB2|')?state.code:'';$('btCopy').disabled=!$('btCode').value;
 $('btStatus').textContent=(state.bluetoothStatus||'尚未刷新已配对设备')+(bt&&!idle?' · '+({host:'中心已就绪',client:'已连接 / TLS认证 / IMA1',joining:'正在连接'}[mode]||mode):'');
 const found=state.bluetoothDevices||[],key=JSON.stringify(found);if(key!==lastBluetoothDevices){lastBluetoothDevices=key;const old=$('btDevice').value;$('btDevice').replaceChildren(new Option('按房间码中的地址连接',''),...found.map(d=>new Option(d.name+' · '+d.address,d.address)));if(found.some(d=>d.address===old))$('btDevice').value=old;}
 const devices=cap.devices||[],aKey=JSON.stringify(devices);if(aKey!==lastA2dpDevices){lastA2dpDevices=aKey;const old=$('a2dpDevice').value;$('a2dpDevice').replaceChildren(...devices.map(d=>new Option(d.name,d.id)));if(devices.some(d=>d.id===old))$('a2dpDevice').value=old;}
  $('a2dpStatus').textContent=cap.status||'未连接';const lanActive=!idle&&state.transport==='lan';$('a2dpRefresh').disabled=!cap.supported||mode==='a2dp'||lanActive||!!cap.connected;$('a2dpConnect').disabled=!cap.supported||!devices.length||mode==='a2dp'||lanActive||!!cap.connected;$('a2dpDisconnect').disabled=!a2dp||lanActive;$('a2dpDevice').disabled=!cap.supported||mode==='a2dp'||lanActive;
}
function render(){const mode=state.mode||'idle',idle=mode==='idle',room=state.room||{},members=room.members||[],bt=state.transport&&state.transport!=='lan',pageBt=$('localInputPanel').parentElement.id==='btAudioPanels',joined=(mode==='host'||mode==='client'||mode==='a2dp')&&!!bt===pageBt;
    if(state.settings){if(!settingsLoaded){fillSettings(state.settings);settingsLoaded=true;}range=Number(pageBt?state.settings.bluetoothRange||400:state.settings.lanRange||200)/100;if(settingsPending&&settingIds.every(id=>String(state.settings[id])===String(JSON.parse(settingsPending)[id]))){settingsPending='';settingsDirty=false;$('settingsStatus').textContent='已保存全部参数；音量上限即时生效，缓冲与调度下次启用本机音频生效。';}else if(settingsPending&&state.error){settingsPending='';$('settingsStatus').textContent='保存失败：'+state.error;}}
    renderBluetooth(idle,bt);
    renderRanges();
    $('autostart').disabled=!state.supportsAutostart;$('startupHint').textContent=state.supportsAutostart?'仅为当前 Windows 用户设置；保存后指向当前 EXE，移动程序后请重新保存。':'Android 不提供 Windows 登录自启；房间运行时由前台服务维持。';
    $('connection').textContent=(({idle:'未连接',host:'房间中心',joining:'连接中',client:'房间成员',a2dp:'直接连接 / A2DP'})[mode]||mode)+(idle?'':' · '+(bt?'蓝牙':'局域网'));
    $('error').hidden=!state.error;$('error').textContent=state.error||'';$('roomCode').value=state.code?.startsWith('AH2|')?state.code:'';
    $('host').disabled=!idle;$('join').disabled=!idle;$('joinCode').disabled=!idle;$('address').disabled=!idle;$('port').disabled=!idle;$('leave').disabled=idle||!!bt;$('copy').disabled=!state.code?.startsWith('AH2|');
    const playing=(state.localOutputs||[]).length>0,busy=!!state.pendingAudio;
    $('start').disabled=!joined||playing||busy;$('stop').disabled=!joined||!playing||busy;$('send').disabled=!joined||busy;$('source').disabled=!joined||!!state.localSend||busy;
    if(!busy)$('send').checked=!!state.localSend;
    $('selfTest').disabled=mode!=='host'||!!bt;
    $('centerInputs').hidden=mode!=='host'&&mode!=='a2dp';$('inputRoleHint').textContent=!joined&&!idle?'当前会话属于另一传输页面；此处不会抢占已有音频。':mode==='a2dp'?'标准 A2DP：此处发送开关仅启用所选 Windows 播放端点回采，参与本机软件混音；不是向软件房间中心发送。':mode==='host'?'中心设备：管理房间各输入增益；也可以选择发送中心本机声音。':'房间成员：将本机媒体声音发送给中心设备，参与统一混音。';
    $('localInfo').textContent=(state.name||'')+' · 发送：'+(state.localSend?'已启用':'未启用')+' · 本机播放：'+(playing?'已启用':'未启用');$('audioStatus').textContent=state.audioStatus||'';
    const addresses=state.addresses||[],key=JSON.stringify([state.devices,state.networkInterfaces,addresses]);if(key!==lastDevices){lastDevices=key;
        const previous=$('address').value||preferences.address,networks=state.networkInterfaces||addresses.map(a=>({address:a,label:a}));$('address').replaceChildren(...networks.map(a=>{const o=text('option',a.label);o.value=a.address;return o;}));if(addresses.includes(previous))$('address').value=previous;
        const source=devicesRendered?$('source').value:preferences.source,selected=devicesRendered?Array.from(document.querySelectorAll('#deviceOutputs input:checked')).map(i=>i.value):(preferences.outputs||[]);$('source').replaceChildren();$('deviceOutputs').replaceChildren();
        for(const d of state.devices||[]){if(d.input){const o=text('option',d.name);o.value=d.id;$('source').append(o);}if(d.output){const l=text('label','','check'),i=document.createElement('input');i.type='checkbox';i.value=d.id;i.checked=selected.includes(d.id);i.addEventListener('change',rememberDevices);l.append(i,text('span',d.name));$('deviceOutputs').append(l);}}
        if(source){if(!Array.from($('source').options).some(o=>o.value===source)){const missing=text('option','原输入已移除，请重新选择');missing.value=source;missing.disabled=true;$('source').append(missing);}$('source').value=source;}
        for(const id of selected)if(!(state.devices||[]).some(d=>d.output&&d.id===id)){const label=text('label','','check'),i=document.createElement('input');i.type='checkbox';i.value=id;i.checked=true;i.addEventListener('change',rememberDevices);label.append(i,text('span','原输出已移除（取消勾选后重新选择）'));$('deviceOutputs').append(label);}
        devicesRendered=true;
    }
    renderDirectCapture();
    $('inputControlHint').textContent=mode==='a2dp'?'直接连接中，此开关与上方“开启/关闭回采”同步，仅控制本机回采，不向软件房间发送。无需重复勾选；关闭不影响已启用的本机播放，也不退出直接连接。':'勾选立即启动向中心发送，取消勾选只停止发送，不影响本机播放。手机/平板首次开启需完成系统媒体捕获授权；打开程序或加入房间不会自动采集。';
    if(mode==='a2dp'&&state.localSend&&state.localSource)$('source').value=state.localSource;
    document.querySelectorAll('#deviceOutputs input').forEach(e=>e.disabled=playing||busy);
    $('counts').textContent=(room.inputs||0)+' / 5 输入 · '+(room.outputs||0)+' / 5 输出';
    const shown=overviewMembers(members),layout=JSON.stringify([room.center,room.centerName,shown.map(m=>[m.id,m.name,m.send,m.source,m.ready,m.outputs,m.devices])]);
    if(layout!==lastRoom){lastRoom=layout;$('inputNodes').replaceChildren();$('outputNodes').replaceChildren();$('centerNode').replaceChildren(text('strong',room.center?'房间中心':'尚未加入房间'),text('span',room.centerName||'请在局域网页创建或加入'));
        for(const m of shown){const available=[...m.devices];if(m.send&&!available.some(d=>d.input&&(d.id===m.source||!m.source)))available.push({id:m.source||'active-input',name:'已启用输入（设备清单待更新）',input:true});for(const o of m.outputs||[])if(!available.some(d=>d.output&&d.id===o))available.push({id:o,name:o,output:true});
            for(const d of available)for(const input of [true,false])if(input?d.input:d.output){const enabled=(m.ready!==false)&&(input?m.send&&(m.source?m.source===d.id:available.filter(d=>d.input).length===1):(m.outputs||[]).includes(d.id));const node=text('div','','node'+(enabled?' active':''));node.dataset.active=String(!!enabled);node.append(text('strong',input?m.name:d.name),text('small',(input?d.name:m.name)+(m.id===state.id?' · 本机':'')),text('small',enabled?'已启用':'已识别 · 未启用','node-state'));if(input){node.dataset.member=m.id;const meter=text('div','','meter');meter.append(text('span',''));node.append(meter);}$(input?'inputNodes':'outputNodes').append(node);}
        }
        if(!$('inputNodes').children.length)$('inputNodes').append(text('div','未识别到输入','empty'));if(!$('outputNodes').children.length)$('outputNodes').append(text('div','未识别到输出','empty'));requestAnimationFrame(arrows);
    }
    document.querySelectorAll('[data-member]').forEach(e=>{const m=members.find(m=>m.id===e.dataset.member);e.querySelector('.meter span').style.width=(e.classList.contains('active')?Math.min(100,(m?.peak||0)*100):0)+'%';});
    syncGainLimits();
    const inputs=members.filter(m=>m.send),gainKey=JSON.stringify([mode,joined,range,inputs.map(m=>[m.id,m.name,m.gain])]);
    if(gainKey!==lastGains&&!document.querySelector('#inputGains input:focus')){lastGains=gainKey;$('inputGains').replaceChildren(...inputs.map((m,i)=>gainRow('输入设备 '+(i+1)+' · '+m.name,m.gain,range*100,joined&&(mode==='host'||mode==='a2dp'),gain=>command('inputGain',{id:m.id,gain}))));if(!inputs.length)$('inputGains').append(text('p','尚无启用的输入','muted'));}
    const local=members.find(m=>m.id===state.id),outputs=state.running?(state.localOutputs||local?.outputs||[]):[],outputKey=JSON.stringify([outputs,joined,range,state.outputGains]);
    if(outputKey!==lastOutputs&&!document.querySelector('#outputGains input:focus')){lastOutputs=outputKey;if(!outputs.length)outputValues={};$('outputGains').replaceChildren(...outputs.map((o,i)=>gainRow((state.devices||[]).find(d=>d.id===o)?.name||o,state.outputGains?.[o]??outputValues[o]??.25,range*100,joined,gain=>{outputValues[o]=gain;command('outputGain',{index:i,gain});})));if(!outputs.length)$('outputGains').append(text('p','尚无本机播放输出','muted'));}
    renderLogs();
    renderAudioFlow();
}
function arrows(){if(tab!=='overview')return;const stage=$('topology'),rect=stage.getBoundingClientRect(),center=$('centerNode').getBoundingClientRect(),g=$('arrows').querySelector('g');g.replaceChildren();if(!state.room?.center)return;for(const [id,input] of [['inputNodes',true],['outputNodes',false]])for(const node of $(id).querySelectorAll('.node.active')){const r=node.getBoundingClientRect(),x1=(input?r.right:center.right)-rect.left,y1=(input?(r.top+r.bottom)/2:(center.top+center.bottom)/2)-rect.top,x2=(input?center.left:r.left)-rect.left,y2=(input?(center.top+center.bottom)/2:(r.top+r.bottom)/2)-rect.top;const p=document.createElementNS('http://www.w3.org/2000/svg','path');p.setAttribute('d',`M${x1},${y1} C${(x1+x2)/2},${y1} ${(x1+x2)/2},${y2} ${x2-3},${y2}`);p.setAttribute('marker-end','url(#arrow)');g.append(p);}}
new ResizeObserver(arrows).observe($('topology'));
async function refresh(){if(polling)return;polling=true;try{state=android?JSON.parse(AudioHubNative.state()):await(await fetch('state')).json();render();}catch(e){$('connection').textContent='引擎连接中';}finally{polling=false;}}
refresh();setInterval(refresh,700);
