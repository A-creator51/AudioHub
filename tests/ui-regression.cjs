const {chromium}=require('./browser.cjs');
const path=require('path');
(async()=>{
 const browser=await chromium.launch({...require('./browser.cjs').launchOptions,headless:true});
 try{
 const page=await browser.newPage({viewport:{width:1120,height:860}});
 await page.addInitScript(()=>{window.__commands=[];window.__state={mode:'idle',devices:[{id:'one',name:'输出一',input:true,output:true}],addresses:['192.168.137.1'],logs:[],room:{},settings:{outputMs:160,reserveMs:100,inputMs:100,mmcss:true,lanRange:200,bluetoothRange:400,captureDetail:false,autostart:false,followLog:true,logLevel:'INFO'}};window.AudioHubNative={state:()=>JSON.stringify(window.__state),command:form=>{window.__commands.push(form);const p=Object.fromEntries(new URLSearchParams(form));if(p.action==='saveSettings'){delete p.action;window.__state.settings=p;}if(p.action==='refreshDevices')window.__state.devices.push({id:'two',name:'输出二',input:true,output:true});}}});
 await page.goto('file:///'+path.resolve(__dirname,'../src/ui/index.html').replace(/\\/g,'/'));
 const failures=[];
 const headings=await page.locator('#lan article h2').allTextContents();
 if(headings.join('|')!=='房间管理|本机输入|本机输出')failures.push('LAN module order: '+headings.join(' → '));
 if(await page.locator('#refreshDevices').count()!==1)failures.push('missing playback device refresh');
 await page.getByRole('button',{name:'日志',exact:true}).click();
 const height=await page.locator('#logText').evaluate(e=>e.getBoundingClientRect().height);
 if(height<400)failures.push('log area too short: '+height+'px');
 for(const id of ['outputMs','reserveMs','inputMs','mmcss','lanRange','bluetoothRange','captureDetail','bufferPreset','saveSettings','defaultSettings'])if(await page.locator('#'+id).count()!==1)failures.push('missing setting: '+id);
 if(failures.length)throw Error(failures.join('\n'));
 await page.getByRole('button',{name:'局域网',exact:true}).click();await page.locator('#deviceOutputs input').check();await page.locator('#refreshDevices').click();await page.waitForTimeout(900);
 if(await page.locator('#deviceOutputs input').count()!==2||!await page.locator('#deviceOutputs input').first().isChecked())throw Error('refresh must update devices and preserve checked outputs');
 await page.getByRole('button',{name:'设置',exact:true}).click();await page.locator('#bufferPreset').selectOption('stable');await page.locator('#saveSettings').click();await page.waitForTimeout(900);
 if(!(await page.evaluate(()=>window.__commands)).some(c=>c.includes('action=saveSettings')&&c.includes('outputMs=400')&&c.includes('reserveMs=200')&&c.includes('inputMs=160')))throw Error('preset not sent to backend');
 if(!await page.locator('#settingsStatus').textContent().then(s=>s.includes('已保存')))throw Error('saved settings confirmation missing');
 await page.locator('#defaultSettings').click();if(await page.locator('#outputMs').inputValue()!=='160')throw Error('default buffers not restored');
 await page.screenshot({path:path.resolve(__dirname,'../build/ui/ui-settings-desktop.png'),fullPage:true});
 await page.getByRole('button',{name:'日志',exact:true}).click();await page.screenshot({path:path.resolve(__dirname,'../build/ui/ui-logs-desktop.png')});
 console.log('PASS LAN order, explicit refresh preserves selection, large logs, settings preset/save/default workflows');
 }finally{await browser.close()}
})().catch(e=>{console.error(e.message);process.exitCode=1});
