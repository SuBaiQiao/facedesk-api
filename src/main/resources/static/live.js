'use strict';
// 实时工作台：USB在浏览器取流并串行提交，RTSP由服务端取流；画框只使用最新返回结果。
(() => {
  const $=id=>document.getElementById(id),base=new URL('admin/live/',new URL('./',location.href));
  const labels={MATCHED:'已匹配',UNKNOWN:'未匹配',TOO_SMALL:'人脸过小'};
  const states={CONNECTING:'正在连接',RUNNING:'实时识别中',STOPPED:'已停止',FAILED:'连接或识别异常'};
  const video=$('live-usb-video'),image=$('live-rtsp-image'),overlay=$('live-overlay'),capture=document.createElement('canvas');
  let session=null,stream=null,generation=0,frameTimer,pollTimer,staleTimer,frameAbort,lastSequence=-1,latest=null,busy=false,lastMediaTime=-1,busyRetries=0;
  const source=()=>document.querySelector('input[name="live-source"]:checked').value;
  function error(message){$('live-error').textContent=message||'';$('live-error').hidden=!message;}
  function controls(value){busy=value;for(const node of $('live-form').querySelectorAll('input,select'))node.disabled=value;$('live-start').disabled=value;$('live-refresh-devices').disabled=value;$('live-stop').disabled=!value;}
  async function api(path,options={},timeout=15000){
    const own=new AbortController(),timer=setTimeout(()=>own.abort(),timeout),signal=options.signal||own.signal;
    try{const response=await fetch(new URL(path,base),{...options,signal,cache:'no-store',headers:{'X-Face-Admin':'1',...(options.headers||{})}});const result=await response.json();if(!response.ok||String(result.code)!=='200'){const failure=new Error(result.msg||'摄像头请求失败');failure.status=response.status;throw failure;}return result.data;}
    finally{clearTimeout(timer);}
  }
  function stopTracks(){if(stream)stream.getTracks().forEach(track=>track.stop());stream=null;video.srcObject=null;video.hidden=true;}
  function clearOverlay(){const c=overlay.getContext('2d');c.clearRect(0,0,overlay.width,overlay.height);}
  function resetDisplay(){lastSequence=-1;lastMediaTime=-1;busyRetries=0;latest=null;clearOverlay();$('live-capture-grid').replaceChildren();$('live-captures-empty').hidden=false;for(const key of ['frames','faces','matched','history-count'])$('live-'+key).textContent='0';image.hidden=true;image.removeAttribute('src');$('live-timestamp').textContent='等待画面';}
  function draw(){
    if(overlay.width!==overlay.clientWidth)overlay.width=overlay.clientWidth;if(overlay.height!==overlay.clientHeight)overlay.height=overlay.clientHeight;clearOverlay();if(!latest||!latest.width||!latest.height)return;
    const c=overlay.getContext('2d'),scale=Math.min(overlay.width/latest.width,overlay.height/latest.height),ox=(overlay.width-latest.width*scale)/2,oy=(overlay.height-latest.height*scale)/2;
    c.lineWidth=2;c.font='12px "Microsoft YaHei",sans-serif';
    for(const face of latest.faces||[]){const b=face.box,x=ox+b.left*scale,y=oy+b.top*scale,w=(b.right-b.left)*scale,h=(b.bottom-b.top)*scale;
      const color=face.confirmed?'#00e5b5':'#ffbe6a',text=face.match?(face.confirmed?`${face.match.name||face.match.code} · ${face.match.score.toFixed(3)}`:'身份确认中'):(face.displayState==='RECHECK'?'识别复核中':labels[face.status]||face.status);
      c.strokeStyle=color;c.strokeRect(x,y,w,h);const width=Math.min(overlay.width-4,c.measureText(text).width+12),left=Math.max(2,Math.min(x,overlay.width-width-2)),top=Math.max(2,y-24);
      c.fillStyle='#021523e8';c.fillRect(left,top,width,22);c.fillStyle=color;c.fillText(text,left+6,top+15,Math.max(1,width-12));
    }
  }
  // 悬停、键盘聚焦或录入弹窗打开时固定抓拍列表，避免点击目标移动；实时画面仍继续识别。
  function captureCards(data){
    const grid=$('live-capture-grid');
    if($('face-dialog').open || grid.matches(':hover') || grid.contains(document.activeElement))return;
    for(const face of data.faces||[]){
      if(!face.thumbnail)continue;
      if((face.match&&!face.confirmed)||face.displayState==='RECHECK')continue;
      const card=document.createElement('article');card.className='video-face '+face.status.toLowerCase();
      const photo=document.createElement('div');photo.className='video-crop';
      if(face.thumbnail){const img=document.createElement('img');img.src=face.thumbnail;img.alt='实时检测到的人脸抓拍';photo.appendChild(img);}
      const tag=document.createElement('span');tag.className='video-result-tag';tag.textContent=labels[face.status]||face.status;photo.appendChild(tag);
      const copy=document.createElement('div');copy.className='video-face-copy';const title=document.createElement('h3');title.textContent=face.match?face.match.name||face.match.code:'未匹配人员';
      const detail=document.createElement('p');detail.textContent=face.match?`${face.match.code} · 相似度 ${face.match.score.toFixed(3)}`:face.status==='TOO_SMALL'?'人脸尺寸不足，未提取特征':'未达到底库匹配条件';
      const timestamp=document.createElement('p');timestamp.textContent=new Date(data.timestamp).toLocaleTimeString('zh-CN',{hour12:false});copy.append(title,detail,timestamp);
      if(face.status==='UNKNOWN'&&face.enrollmentImage){
        const enroll=document.createElement('button');enroll.type='button';enroll.className='btn capture-enroll';enroll.textContent='录入档案';
        enroll.addEventListener('click',()=>window.dispatchEvent(new CustomEvent('face-enroll-capture',{detail:{
          image:face.enrollmentImage,role:$('live-role').value,sourceLabel:(data.source==='RTSP'?'RTSP 摄像头':'USB 摄像头')+' · '+timestamp.textContent,
          onSaved:()=>{enroll.textContent='已录入档案';enroll.disabled=true;}
        }})));copy.appendChild(enroll);
      }
      card.append(photo,copy);grid.prepend(card);
    }
    while(grid.children.length>12)grid.lastChild.remove();$('live-captures-empty').hidden=Boolean(grid.children.length);
  }
  function apply(data){
    if(data.sequence<lastSequence&&!['FAILED','STOPPED'].includes(data.state))return;
    $('live-state').textContent=states[data.state]||data.state;$('live-state').className='badge '+(data.state==='RUNNING'?'success':data.state==='FAILED'?'error':'neutral');
    $('live-frames').textContent=data.sequence.toLocaleString();$('live-faces').textContent=data.totalFaces.toLocaleString();$('live-matched').textContent=data.matched.toLocaleString();$('live-history-count').textContent=data.historyEvents.toLocaleString();
    if(data.sequence>lastSequence&&data.faces){lastSequence=data.sequence;latest=data;$('live-frame-label').textContent=`第 ${data.sequence} 次画面识别`;
      $('live-timestamp').textContent=data.timestamp?new Date(data.timestamp).toLocaleString('zh-CN',{hour12:false}):'等待画面';
      if(data.image){image.hidden=false;image.onload=draw;image.src=data.image;}else draw();captureCards(data);
      clearTimeout(staleTimer);staleTimer=setTimeout(()=>{latest=null;clearOverlay();$('live-frame-label').textContent='等待新的识别画面';},data.source==='USB'?700:2500);
    }
    if(data.state==='FAILED'||data.state==='STOPPED'){if(data.error)error(data.error);stop(false);}
  }
  async function devices(){
    if(!navigator.mediaDevices?.enumerateDevices)return;
    const selected=$('live-device').value;
    try{const items=await navigator.mediaDevices.enumerateDevices();$('live-device').replaceChildren(new Option('默认摄像头',''));let n=0;for(const d of items)if(d.kind==='videoinput')$('live-device').appendChild(new Option(d.label||'摄像头 '+(++n),d.deviceId));if([...$('live-device').options].some(o=>o.value===selected))$('live-device').value=selected;}
    catch(e){error('无法读取摄像头列表，请检查浏览器权限。');}
  }
  async function capabilities(){try{const result=await api('capabilities');$('live-decoder-status').textContent=result.rtsp?'服务器解码器：FFmpeg 已就绪':'服务器解码器：未找到 FFmpeg，请先安装或配置路径';}catch(e){$('live-decoder-status').textContent='服务器解码器：检查失败，可尝试连接后查看提示';}}
  // 上一次上传和识别完成后才采集下一帧；代次号阻止停止/重连后的旧回调重新启动循环。
  async function sendFrame(token){
    if(token!==generation||!session||!stream)return;
    const started=performance.now();
    try{
      if(!video.videoWidth||!video.videoHeight)throw new Error('USB 摄像头没有可用画面');
      if(video.currentTime===lastMediaTime){frameTimer=setTimeout(()=>sendFrame(token),20);return;}lastMediaTime=video.currentTime;
      const scale=Math.min(1,1280/video.videoWidth,720/video.videoHeight),width=Math.max(1,Math.round(video.videoWidth*scale)),height=Math.max(1,Math.round(video.videoHeight*scale));if(capture.width!==width)capture.width=width;if(capture.height!==height)capture.height=height;capture.getContext('2d').drawImage(video,0,0,capture.width,capture.height);
      const blob=await new Promise((resolve,reject)=>capture.toBlob(value=>value?resolve(value):reject(new Error('摄像头画面读取失败')),'image/jpeg',.82));if(token!==generation)return;
      const form=new FormData();form.append('frame',blob,'camera.jpg');const requestAbort=new AbortController();frameAbort=requestAbort;const timeout=setTimeout(()=>requestAbort.abort(),75000);
      try{const data=await api(encodeURIComponent(session)+'/frame',{method:'POST',body:form,signal:requestAbort.signal},75000);if(token===generation){busyRetries=0;apply(data);if(session)$('live-frame-label').textContent=`第 ${data.sequence} 次 · 请求耗时 ${Math.round(performance.now()-started)} ms`;}}finally{clearTimeout(timeout);if(frameAbort===requestAbort)frameAbort=null;}
    }catch(e){if(token===generation){if(e.status===503&&++busyRetries<=3){frameTimer=setTimeout(()=>sendFrame(token),150);return;}error(e.name==='AbortError'?'摄像头识别超时，请重连后重试。':e.message);await stop(true);return;}}
    if(token===generation&&session)frameTimer=setTimeout(()=>sendFrame(token),Math.max(0,100-(performance.now()-started)));
  }
  async function poll(token){
    if(token!==generation||!session)return;
    try{const data=await api(encodeURIComponent(session)+'?after='+lastSequence);if(token===generation)apply(data);}
    catch(e){if(token===generation){error('摄像头连接已中断，请重新连接。');await stop(true);return;}}
    if(token===generation&&session)pollTimer=setTimeout(()=>poll(token),stream?5000:500);
  }
  // 先失效代次并停止定时器、请求和本地设备，再通知服务端结束会话。
  async function stop(remote=true){
    const id=session;session=null;generation++;clearTimeout(frameTimer);clearTimeout(pollTimer);clearTimeout(staleTimer);frameAbort?.abort();frameAbort=null;stopTracks();latest=null;clearOverlay();image.hidden=true;image.removeAttribute('src');$('live-placeholder').hidden=false;controls(false);
    if($('live-state').textContent!=='连接或识别异常'){$('live-state').textContent='已停止';$('live-state').className='badge neutral';}$('live-frame-label').textContent='摄像头已停止';
    if(remote&&id)try{await api(encodeURIComponent(id)+'/stop',{method:'POST'});}catch(e){error('停止请求未送达服务器；超过 30 秒未响应后会自动释放会话。');}
  }
  $('live-form').addEventListener('submit',async event=>{
    event.preventDefault();if(busy)return;const token=++generation,type=source();error('');resetDisplay();controls(true);$('live-state').textContent='正在连接';$('live-state').className='badge neutral';
    try{
      if(type==='USB'){
        if(!navigator.mediaDevices?.getUserMedia)throw new Error('浏览器无法访问摄像头。请通过 HTTPS 或 localhost 打开页面。');
        const device=$('live-device').value,opened=await navigator.mediaDevices.getUserMedia({audio:false,video:{...(device?{deviceId:{exact:device}}:{}),width:{ideal:1280},height:{ideal:720},frameRate:{ideal:30}}});
        if(token!==generation){opened.getTracks().forEach(track=>track.stop());return;}
        stream=opened;video.srcObject=stream;video.hidden=false;await video.play();
        for(const track of stream.getVideoTracks())track.addEventListener('ended',()=>{if(token===generation){error('USB 摄像头已断开或权限已撤回。');stop(true);}});
        devices();
      }
      if(token!==generation)return;
      const result=await api('',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({source:type,name:$('live-name').value.trim(),role:$('live-role').value,rtspUrl:type==='RTSP'?$('live-rtsp-url').value.trim():undefined})});
      if(token!==generation){api(encodeURIComponent(result.id)+'/stop',{method:'POST'}).catch(()=>{});return;}
      session=result.id;$('live-monitor-name').textContent=result.name;$('live-placeholder').hidden=true;apply(result);poll(token);if(type==='USB')sendFrame(token);
    }catch(e){if(token===generation){const messages={NotAllowedError:'浏览器未授予摄像头权限，请在地址栏允许后重试。',NotFoundError:'未找到 USB 摄像头，请检查设备连接。',NotReadableError:'摄像头无法读取，可能被其他程序占用。',OverconstrainedError:'所选摄像头不可用，请刷新设备后重试。'};error(messages[e.name]||e.message||'摄像头连接失败');await stop(true);}}
  });
  $('live-stop').addEventListener('click',()=>stop(true));$('live-refresh-devices').addEventListener('click',devices);
  document.querySelectorAll('input[name="live-source"]').forEach(input=>input.addEventListener('change',()=>{$('live-usb-settings').hidden=source()!=='USB';$('live-rtsp-settings').hidden=source()!=='RTSP';if(source()==='RTSP')capabilities();else devices();error('');}));
  $('live-show-url').addEventListener('click',()=>{const show=$('live-rtsp-url').type==='password';$('live-rtsp-url').type=show?'text':'password';$('live-show-url').textContent=show?'隐藏地址':'显示地址';$('live-show-url').setAttribute('aria-pressed',String(show));});
  new ResizeObserver(draw).observe($('live-stage'));
  window.addEventListener('hashchange',()=>{if(location.hash!=='#live'&&busy)stop(true);else if(location.hash==='#live'){devices();capabilities();}});
  // 页面离开时尽力发送keepalive停止请求；发送失败由服务端会话租约兜底释放资源。
  window.addEventListener('pagehide',()=>{if(session)fetch(new URL(encodeURIComponent(session)+'/stop',base),{method:'POST',headers:{'X-Face-Admin':'1'},keepalive:true}).catch(()=>{});stopTracks();});
  if(location.hash==='#live'){devices();capabilities();}
})();
