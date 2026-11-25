const {chromium}=require('./browser.cjs');
const path=require('path');
(async()=>{
 const browser=await chromium.launch({...require('./browser.cjs').launchOptions,headless:true});
 try{const p=await browser.newPage();await p.addInitScript(()=>{window.__state={mode:'host',id:'h',name:'Host',running:true,localSend:true,localOutputs:['o'],devices:[{id:'o',name:'Speaker',output:true}],room:{center:'h',members:[{id:'h',name:'Host',send:true,gain:2,outputs:['o']}]},settings:{lanRange:200,bluetoothRange:400},logs:[]};window.AudioHubNative={state:()=>JSON.stringify(window.__state),command:()=>{}}});
 await p.goto('file:///'+path.resolve(__dirname,'../src/ui/index.html').replace(/\\/g,'/'));
 await p.evaluate(()=>window.__state.settings.lanRange=100);await p.waitForTimeout(900);
 if(await p.locator('#inputGains input').getAttribute('max')!=='100')throw Error('input slider ignores lowered volume range');
 console.log('PASS range changes update sliders');
 }finally{await browser.close()}
})().catch(e=>{console.error(e.message);process.exitCode=1});
