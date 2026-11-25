'use strict';
const {spawnSync}=require('child_process'),path=require('path');
for(const file of ['ui-regression','ui-routing','ui-check','ui-volume','ui-bluetooth','ui-live-range','ui-direct-capture']){
 const result=spawnSync(process.execPath,[path.join(__dirname,file+'.cjs')],{stdio:'inherit',env:process.env});
 if(result.error||result.status!==0){console.error('UI regression failed: '+file);process.exit(1);}
}
