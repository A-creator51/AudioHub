'use strict';
// Reports file/rule only, never matched values or source lines.
const fs=require('fs'),path=require('path'),cp=require('child_process');
const root=path.resolve(__dirname,'..');
function git(args){return cp.execFileSync('git',['-C',root,...args],{encoding:'utf8',stdio:['ignore','pipe','pipe']});}
function candidates(){return [...new Set(git(['ls-files','-z','--cached','--others','--exclude-standard']).split('\0').filter(Boolean))];}
const allowedTop=new Set(['src','tests','scripts','docs','third_party','.github']);
const allowedFiles=new Set(['README.md','LICENSE','DISCLAIMER.md','THIRD_PARTY_NOTICES.md','SECURITY.md','CONTRIBUTING.md','CHANGELOG.md','.gitignore','.gitattributes','.env.example','package.json','package-lock.json']);
const patterns=[
 ['personal-local-path',/(?:\b[A-Za-z]:[\\/]|\/Users\/|\/home\/)[^\s"'<>]*/i],
 ['private-key',/-----BEGIN (?:RSA |EC |OPENSSH |ENCRYPTED )?PRIVATE KEY-----/],
 ['github-token',/\b(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{30,})\b/],
 ['cloud-access-key',/\b(?:AKIA|ASIA)[A-Z0-9]{16}\b/],
 ['actual-lan-room-code',/\bAH2\|[A-Za-z0-9.:-]+\|\d{1,5}\|[0-9a-f]{64}\|[0-9a-f]{32}\b/i],
 ['actual-bluetooth-room-code',/\bAHB2\|(?:PAIRED|(?:[0-9a-f]{2}:){5}[0-9a-f]{2})\|[0-9a-f]{64}\|[0-9a-f]{32}\|IMA1\b/i]
];
function check(){const failures=[];let files;
 try{files=candidates();}catch{throw Error('Run git init in this new repository first. No remote or commit is required.');}
 const envFile=path.join(root,'.env');const localSecrets=[];
 if(fs.existsSync(envFile)){for(const line of fs.readFileSync(envFile,'utf8').split(/\r?\n/)){const m=line.match(/^([A-Z][A-Z0-9_]*)=(.*)$/);if(m&&m[2].length>=12&&(/PASSWORD|TOKEN|SECRET/.test(m[1])||/^[A-Za-z]:[\\/]/.test(m[2])))localSecrets.push(m[2]);}}
 for(const file of files){const norm=file.replace(/\\/g,'/');if(!allowedFiles.has(norm)&&!allowedTop.has(norm.split('/')[0])){failures.push([file,'unexpected-public-file']);continue;}
  if(/(^|\/)\.env(?:\.|$)/.test(norm)&&norm!=='.env.example'||/(^|\/)(?:build|dist|node_modules|\.signing|\.local|\.git|\.gradle)(\/|$)/.test(norm)||/\.(?:p12|pfx|jks|keystore|pem|key|apk|aab|log|class|zip)$/i.test(norm)){failures.push([file,'private-or-generated-file']);continue;}
  const full=path.join(root,file),stat=fs.lstatSync(full);if(stat.isSymbolicLink()){failures.push([file,'symlink-not-allowed']);continue;}if(!stat.isFile())continue;
  const buf=fs.readFileSync(full),data=buf.toString('utf8')+'\n'+buf.toString('utf16le');
  for(const [rule,re] of patterns)if(re.test(data))failures.push([file,rule]);
  if(localSecrets.some(secret=>data.includes(secret)))failures.push([file,'local-env-value']);
 }
 for(const file of ['.env','.signing/audiohub-release.p12','.local/probe.txt','build/probe.txt','dist/probe.txt']){try{git(['check-ignore','-q','--no-index',file]);}catch{failures.push([file,'ignore-rule-missing']);}}
 if(failures.length){for(const [file,rule] of failures)console.error('FAIL '+rule+' : '+file);throw Error('Privacy gate failed; matched values suppressed.');}
 console.log('PASS privacy gate: '+files.length+' public candidates; no configured secret/path signatures; private file ignore rules verified.');
 return files;
}
if(require.main===module){try{check();}catch(e){console.error(e.message);process.exitCode=1;}}
module.exports={check,root};
