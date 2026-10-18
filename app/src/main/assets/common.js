"use strict";
// Shared helpers for Safar (journey) mode and Walk mode. Loaded before each page's own script.
const $=id=>document.getElementById(id);
const A=window.AndroidSafar;
function show(t){const o=$("out");if(o)o.textContent=t}
window.addEventListener("error",e=>show("त्रुटि: "+e.message));
if(!A){show("यह मोड सिर्फ़ ऐप में चलता है।");throw new Error("no native bridge")}

// ---------------------------------------------------------------- storage
const store={
 get(k,d){try{const v=localStorage.getItem(k);return v?JSON.parse(v):d}catch(e){return d}},
 set(k,v){try{localStorage.setItem(k,JSON.stringify(v))}catch(e){}}};
const RATE=(()=>{try{return parseFloat(localStorage.getItem("r"))||1}catch(e){return 1}})();
const sleep=ms=>new Promise(r=>setTimeout(r,ms));

// ---------------------------------------------------------------- speech out (resolves when finished)
// lead: "chime" = musical chime first, "arrive" = arrival tune first
const ttsWait={};let sid=0;
window.onTtsDone=id=>{const f=ttsWait[id];if(f){delete ttsWait[id];f()}};
function say(t,flush,lead){
 show(t);
 return new Promise(res=>{
  const id="s"+(++sid);
  const to=setTimeout(()=>{delete ttsWait[id];res()},25000);
  ttsWait[id]=()=>{clearTimeout(to);res()};
  A.speak(t,RATE,!!flush,id,lead||"")})}

// ---------------------------------------------------------------- speech in
let lastErr="",heardCb=null;
window.onHeard=arr=>{const f=heardCb;heardCb=null;f&&f(arr)};
window.onHeardErr=c=>{lastErr=c;const f=heardCb;heardCb=null;f&&f(null)};
function listen(){
 return new Promise(res=>{
  let done=false;
  const to=setTimeout(()=>{if(!done){done=true;heardCb=null;try{A.cancelListen()}catch(e){}res(null)}},15000);
  heardCb=arr=>{if(done)return;done=true;clearTimeout(to);res(arr&&arr.length?arr:null)};
  lastErr="";A.vibrate("0,60");A.listen()})}
async function ask(prompt,tries){
 for(let i=0;i<(tries||2);i++){
  await say(i?"समझ नहीं आया। "+prompt:prompt,true);
  await sleep(300);
  const r=await listen();
  if(r)return r;
  if(lastErr==="unavailable"||lastErr==="perm")break}
 return null}
const YESW=["हाँ","हां","हा","हाँजी","जी","सही","ठीक","ओके","ok","okay","yes","yeah","haan","होय","हो"];
const NOW=["नहीं","नही","ना","नाही","नो","no","गलत","nahi"];
function yn(arr){
 for(const s of arr){
  const w=s.toLowerCase().trim().split(/\s+/);
  if(w.length>4)continue;
  if(w.some(x=>NOW.includes(x)))return"n";
  if(w.some(x=>YESW.includes(x)))return"y"}
 return null}
const clean=s=>s.replace(/^(यह|ये|इसका नाम|नाम|जगह)\s+/,"").replace(/\s+(है|हैं|हे)$/,"").trim();
async function noVoiceMsg(){
 if(lastErr==="unavailable")await say("इस फ़ोन में आवाज़ पहचानने वाली सेवा नहीं मिली।",true);
 else if(lastErr==="perm")await say("माइक की अनुमति दीजिए।",true)}

// ---------------------------------------------------------------- web requests (through the app)
const hcb={};let hid=0;
window.onHttp=(id,status,text)=>{const f=hcb[id];if(f){delete hcb[id];f(status,text)}};
function http(method,url,body){
 return new Promise((res,rej)=>{
  const id="h"+(++hid);
  hcb[id]=(s,t)=>s>=200&&s<300?res(t):rej(new Error("HTTP "+s+" "+String(t).slice(0,80)));
  A.http(method,url,body||"",id)})}

// ---------------------------------------------------------------- maths
const rad=x=>x*Math.PI/180;
function hav(a,b,c,d){ // km
 const dl=rad(c-a),dn=rad(d-b);
 const x=Math.sin(dl/2)**2+Math.cos(rad(a))*Math.cos(rad(c))*Math.sin(dn/2)**2;
 return 12742*Math.asin(Math.sqrt(x))}
function bearing(a,b,c,d){ // degrees 0..360, from point 1 to point 2
 const y=Math.sin(rad(d-b))*Math.cos(rad(c));
 const x=Math.cos(rad(a))*Math.sin(rad(c))-Math.sin(rad(a))*Math.cos(rad(c))*Math.cos(rad(d-b));
 return(Math.atan2(y,x)*180/Math.PI+360)%360}
function angDiff(a,b){return((a-b+540)%360)-180} // a-b in (-180,180]; positive = a is clockwise of b
function fmt(d){ // d in km
 if(d<1)return Math.max(1,Math.round(d*10))*100+" मीटर";
 const r=Math.round(d*2)/2;return(Number.isInteger(r)?r:r.toFixed(1))+" किमी"}
function timeStr(m){
 m=Math.max(1,Math.round(m));
 if(m<60)return m+" मिनट";
 const h=Math.floor(m/60),r=m%60;return h+" घंटे"+(r?" "+r+" मिनट":"")}
function clock(d){
 const h=d.getHours(),m=d.getMinutes();
 const p=h>=4&&h<12?"सुबह":h>=12&&h<16?"दोपहर":h>=16&&h<20?"शाम":"रात";
 const h12=h%12===0?12:h%12;
 return p+" "+h12+(m?" बजकर "+m+" मिनट":" बजे")}
function clockAt(d){return clock(d)+(d.getMinutes()?" पर":"")} // for "... निकले"
const dev=s=>/[\u0900-\u097F]/.test(s||"");

// ---------------------------------------------------------------- GPS
// Each fix gets f.v (speed m/s) and f.hd (heading degrees, -1 if not known yet).
// The page defines onFix(f) to receive them.
let fix=null,prevFix=null;const trail=[];
window.onLoc=(lat,lon,sp,acc,t,br)=>{
 const f={lat,lon,sp,acc,t,br};
 let v=sp>=0?sp:0;
 if(sp<0&&prevFix)v=hav(prevFix.lat,prevFix.lon,lat,lon)*1000/Math.max(1,(t-prevFix.t)/1000);
 f.v=v;
 trail.push(f);if(trail.length>12)trail.shift();
 let hd=-1;
 if(br>=0&&v>0.6)hd=br;
 else{for(let i=trail.length-2;i>=0;i--){
  if(hav(trail[i].lat,trail[i].lon,lat,lon)*1000>=8){hd=bearing(trail[i].lat,trail[i].lon,lat,lon);break}}}
 f.hd=hd;
 prevFix=f;fix=f;
 if(typeof onFix==="function")onFix(f)};
window.onLocErr=c=>{if(c==="disabled")say("जीपीएस बंद है। कृपया चालू कीजिए।",false)};
window.onPerm=ok=>say(ok?"अनुमति मिल गई। अब फिर से दबाइए।":"लोकेशन की अनुमति नहीं मिली।",true);
function waitFix(ms){
 return new Promise(res=>{
  const t0=Date.now();
  const iv=setInterval(()=>{
   const fresh=fix&&Date.now()-fix.t<120000;
   if(fresh||Date.now()-t0>ms){clearInterval(iv);res(fresh?fix:null)}},500)})}

// ---------------------------------------------------------------- destination by voice (shared)
async function pickDestination(f,heard){
 let cands=[];
 for(const alt of heard.slice(0,2)){
  const vb=(f.lon-3)+","+(f.lat+3)+","+(f.lon+3)+","+(f.lat-3);
  const u="https://nominatim.openstreetmap.org/search?format=jsonv2&limit=3&countrycodes=in&accept-language=hi&viewbox="+vb+"&bounded=0&q="+encodeURIComponent(clean(alt));
  try{const j=JSON.parse(await http("GET",u));if(j.length){cands=j;break}}catch(e){}
  await sleep(1100)}
 if(!cands.length){await say("यह जगह नहीं मिली। नेट चालू है या नहीं देखिए, और फिर कोशिश कीजिए।",true);return null}
 for(const c of cands){
  const nm=c.display_name.split(",").slice(0,2).join(",");
  const a=await ask(nm+"। सही है?",2);
  if(!a){await say("जवाब नहीं मिला। रद्द।",true);return null}
  if(yn(a)==="y")return{name:c.display_name.split(",")[0].trim(),lat:+c.lat,lon:+c.lon}}
 await say("ठीक है। फिर से कोशिश कीजिए।",true);
 return null}
