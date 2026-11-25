'use strict';
const fs=require('fs'),path=require('path');
fs.mkdirSync(path.join(__dirname,'../build/ui'),{recursive:true});
const playwright=require(process.env.AUDIOHUB_PLAYWRIGHT_MODULE||'playwright');
const launchOptions=process.env.AUDIOHUB_BROWSER_EXECUTABLE?{executablePath:process.env.AUDIOHUB_BROWSER_EXECUTABLE}:process.env.AUDIOHUB_BROWSER_CHANNEL?{channel:process.env.AUDIOHUB_BROWSER_CHANNEL}:{};
module.exports={...playwright,launchOptions};
