'use strict';
// 上传视频工作台：上传进度与后台识别轮询分开，分页结果保留处理时的原始判定。
(() => {
  const $ = id => document.getElementById(id), base = new URL('./',location.href);
  const escape = value => String(value ?? '').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const names = {RUNNING:'正在识别',COMPLETED:'识别完成',CANCELLED:'已取消',FAILED:'处理失败'};
  const labels = {MATCHED:'已匹配',UNKNOWN:'未匹配',TOO_SMALL:'人脸过小'};
  let jobId = null, page = 1, pages = 1, timer, busy = false, polling = false, previewURL, xhr, selected, renderedKey = '';
  const enrolledCaptures = new Set();
  const video = $('video-preview'), canvas = $('video-overlay');
  function error(message) { $('video-error').textContent = message || ''; $('video-error').hidden = !message; }
  function time(seconds) { return `${Math.floor(seconds/60).toString().padStart(2,'0')}:${(seconds%60).toFixed(2).padStart(5,'0')}`; }
  async function api(path,options={}) {
    const controller=new AbortController(), timeout=setTimeout(()=>controller.abort(),15000);
    try {
      const response = await fetch(new URL('admin/videos/'+path,base),{...options,cache:'no-store',signal:controller.signal,headers:{'X-Face-Admin':'1'}});
      const result = await response.json();
      if (!response.ok || String(result.code)!=='200') { const e=new Error(result.msg || '视频请求失败'); e.status=response.status; throw e; }
      return result.data;
    } finally { clearTimeout(timeout); }
  }
  function resetResults() {
    for(const name of ['frames','total','matched','people','unknown','small']) $('video-'+name).textContent='0';
    $('video-result-count').textContent='0 次'; $('video-page-summary').textContent='暂无结果'; $('video-page').textContent='1 / 1';
    $('video-prev').disabled=true; $('video-next').disabled=true; $('video-progress').value=0; $('video-progress-value').textContent='—';
    $('video-result-grid').replaceChildren(); $('video-results-empty').hidden=false; $('video-export').hidden=true;
    $('video-results-empty').querySelector('p').textContent='处理开始后，这里会显示检测到的人脸和识别结果。';
    $('video-retry').hidden=true; selected=null;
  }
  function setBusy(value) {
    busy = value;
    $('video-start').disabled = value;
    $('video-file').disabled = value; $('video-role').disabled = value;
    $('video-cancel').disabled = !value;
  }
  function showStatus(job) {
    const running = job.state==='RUNNING'; setBusy(running);
    $('video-state').textContent = job.cancelRequested && running ? '正在取消' : names[job.state] || job.state;
    $('video-state').className = 'badge '+(job.state==='COMPLETED'?'success':job.state==='FAILED'?'error':running?'neutral':'warning');
    $('video-cancel').disabled = !running || job.cancelRequested;
    $('video-filename').textContent = job.filename;
    $('video-frames').textContent = job.processedFrames.toLocaleString();
    $('video-total').textContent = job.total.toLocaleString();
    $('video-matched').textContent = job.matched.toLocaleString();
    $('video-people').textContent = job.people.toLocaleString();
    $('video-unknown').textContent = job.unknown.toLocaleString();
    $('video-small').textContent = job.tooSmall.toLocaleString();
    const percentage = job.state==='COMPLETED'?100:job.totalFrames>0?Math.min(99.9,job.processedFrames/job.totalFrames*100):null;
    if (percentage===null && running) $('video-progress').removeAttribute('value'); else $('video-progress').value = percentage || 0;
    $('video-progress-value').textContent = percentage===null?'—':percentage.toFixed(1)+'%';
    $('video-progress-label').textContent = `${time(job.timeSeconds)} · ${job.processedFrames} / ${job.totalFrames || '未知'} 帧`;
    $('video-result-count').textContent = job.total.toLocaleString()+' 次';
    $('video-export').hidden = !job.total;
    $('video-export').href = new URL('admin/videos/'+encodeURIComponent(job.id)+'/export',base).href;
    if (job.error) error(job.error+'。当前展示的是已处理部分的结果。');
    $('video-results-empty').hidden = Boolean(job.total);
    if (!job.total) $('video-results-empty').querySelector('p').textContent = running?'正在逐帧检测，尚未发现人脸。':job.state==='COMPLETED'?'视频已处理完成，没有检测到符合检测阈值的人脸。':'暂无人脸结果。';
    return running;
  }
  // 只在暂停且播放时间接近所选抓拍时画框，避免把历史坐标画到其他时间的画面上。
  function drawBox() {
    canvas.width = canvas.clientWidth; canvas.height = canvas.clientHeight;
    const c = canvas.getContext('2d'); c.clearRect(0,0,canvas.width,canvas.height);
    if (!selected || !video.videoWidth || !video.paused || Math.abs(video.currentTime-selected.timeSeconds)>.15) return;
    const scale = Math.min(canvas.width/video.videoWidth,canvas.height/video.videoHeight);
    const x = (canvas.width-video.videoWidth*scale)/2, y = (canvas.height-video.videoHeight*scale)/2, b = selected.box;
    c.strokeStyle = selected.status==='MATCHED'?'#00e5b5':'#ffbf66'; c.lineWidth = 2;
    c.strokeRect(x+b.left*scale,y+b.top*scale,(b.right-b.left)*scale,(b.bottom-b.top)*scale);
  }
  function showResults(result) {
    pages = result.pages; $('video-page').textContent = `${page} / ${pages}`;
    $('video-prev').disabled = page<=1; $('video-next').disabled = page>=pages;
    $('video-page-summary').textContent = `共 ${result.total.toLocaleString()} 次检测 · 每页 24 次`;
    const grid = $('video-result-grid'); grid.replaceChildren();
    for (const item of result.items) {
      const card = document.createElement('article'); card.className = 'video-face '+item.status.toLowerCase();
      const m = item.match;
      card.innerHTML = `<div class="video-crop">${item.thumbnail?`<img alt="第 ${item.frame+1} 帧的人脸抓拍" loading="lazy">`:'<span>无抓拍</span>'}<span class="video-result-tag">${labels[item.status] || escape(item.status)}</span></div><div class="video-face-copy"><h3>${escape(m?m.name || m.code:item.status==='TOO_SMALL'?'尺寸不足，未识别':'未匹配到底库人员')}</h3><p>${m?`${escape(m.code)} · 相似度 ${m.score.toFixed(3)}`:'保留检测人脸抓拍'}</p><button class="video-time" type="button">${time(item.timeSeconds)} <span>第 ${item.frame+1} 帧 ↗</span></button></div>`;
      if (item.thumbnail) card.querySelector('img').src = item.thumbnail;
      const seek = card.querySelector('button'); seek.disabled = !previewURL;
      seek.title = previewURL?'定位原视频的抓拍时间点':'重新选择原视频后可定位时间点';
      seek.addEventListener('click',()=>{ selected=item; video.pause(); video.currentTime=item.timeSeconds; video.scrollIntoView({block:'center',behavior:matchMedia('(prefers-reduced-motion: reduce)').matches?'auto':'smooth'}); drawBox(); });
      // 已录入状态只更新当前页面的操作反馈，不把旧视频事件改写为当时已匹配。
      if(item.status==='UNKNOWN' && item.enrollmentImage) {
        const key=jobId+':'+item.frame+':'+[item.box.left,item.box.top,item.box.right,item.box.bottom].join(',');
        const enroll=document.createElement('button');enroll.type='button';enroll.className='btn capture-enroll';
        enroll.textContent=enrolledCaptures.has(key)?'已录入档案':'录入档案';enroll.disabled=enrolledCaptures.has(key);
        enroll.addEventListener('click',()=>window.dispatchEvent(new CustomEvent('face-enroll-capture',{detail:{
          image:item.enrollmentImage,role:$('video-role').value,sourceLabel:'视频抓拍 · '+time(item.timeSeconds),
          onSaved:()=>{enrolledCaptures.add(key);enroll.textContent='已录入档案';enroll.disabled=true;}
        }})));card.querySelector('.video-face-copy').appendChild(enroll);
      }
      grid.appendChild(card);
    }
  }
  // 任务ID和结果版本双重校验，避免切换视频后旧轮询响应覆盖新任务。
  async function poll(force=false) {
    if (!jobId || polling) return; polling=true; clearTimeout(timer);
    const requestedId=jobId;
    let running = busy;
    try {
      const result = await api(encodeURIComponent(requestedId));
      if(requestedId!==jobId)return;
      error(''); $('video-retry').hidden=true; running=showStatus(result);
      const key = `${page}:${result.total}:${result.state}:${Boolean(previewURL)}`;
      if (force || key!==renderedKey) {
        const data = await api(`${encodeURIComponent(requestedId)}/faces?page=${page}&size=24`);
        if(requestedId!==jobId)return;
        showResults(data); renderedKey=key;
      }
    } catch(e) {
      if(requestedId!==jobId)return;
      error(e.name==='AbortError'?'读取任务超时，请刷新重试。':e.message || '无法读取视频任务，请检查连接后刷新任务。'); $('video-retry').hidden=false;
      if(e.status===400){running=false;setBusy(false);jobId=null;$('video-export').hidden=true;$('video-state').textContent='任务已失效';try{sessionStorage.removeItem('face-video-job');}catch(_){}}
    }
    finally { polling=false; if (jobId && (requestedId!==jobId || running)) timer=setTimeout(()=>poll(),1500); }
  }
  $('video-file').addEventListener('change',()=>{
    const file=$('video-file').files[0]; if (!file) return;
    if (file.size>500*1024*1024) { error('视频不能超过 500 MB'); $('video-file').value=''; return; }
    if (previewURL) URL.revokeObjectURL(previewURL);
    previewURL=URL.createObjectURL(file); video.src=previewURL; selected=null;
    $('video-placeholder').hidden=true; error('');
    if (jobId && !busy) { jobId=null; try { sessionStorage.removeItem('face-video-job'); } catch(_) {} }
    $('video-filename').textContent=file.name;
    $('video-state').textContent='等待开始';
    resetResults(); $('video-progress-label').textContent='视频已选择，等待开始';
  });
  video.addEventListener('error',()=>error('浏览器无法预览此视频。仍可提交服务端识别；服务端支持格式取决于本机解码器。'));
  for (const event of ['seeked','pause','play','loadedmetadata','timeupdate']) video.addEventListener(event,drawBox);
  new ResizeObserver(drawBox).observe(video);
  $('video-form').addEventListener('submit',event=>{
    event.preventDefault(); if (busy) return;
    const file=$('video-file').files[0]; if (!file) return;
    error(''); clearTimeout(timer); jobId=null; renderedKey=''; page=1; setBusy(true);
    try{sessionStorage.removeItem('face-video-job');}catch(_){}
    resetResults();
    $('video-state').textContent='正在上传'; $('video-progress-label').textContent='正在上传至本地服务';
    const data=new FormData(); data.append('video',file); data.append('role',$('video-role').value);
    xhr=new XMLHttpRequest(); xhr.open('POST',new URL('admin/videos',base)); xhr.setRequestHeader('X-Face-Admin','1'); xhr.timeout=300000;
    xhr.upload.onprogress=e=>{ if(e.lengthComputable){ const p=e.loaded/e.total*100; $('video-progress').value=p; $('video-progress-value').textContent=p.toFixed(1)+'%'; } };
    xhr.upload.onload=()=>{ $('video-cancel').disabled=true; $('video-progress-label').textContent='上传完成，正在创建识别任务'; };
    const failed=message=>{xhr=null;setBusy(false);error(message);$('video-state').textContent='未开始';};
    xhr.onerror=()=>failed('视频上传失败，请检查服务连接后重试。'); xhr.ontimeout=()=>failed('视频上传超时，请重试。'); xhr.onabort=()=>failed('已取消上传。');
    xhr.onload=()=>{try{const response=JSON.parse(xhr.responseText);if(xhr.status<200 || xhr.status>=300 || String(response.code)!=='200')throw new Error(response.msg || '上传失败');jobId=response.data.id;try{sessionStorage.setItem('face-video-job',jobId);}catch(_){}xhr=null;poll(true);}catch(e){failed(e.message);}};
    xhr.send(data);
  });
  $('video-cancel').addEventListener('click',async()=>{
    if(xhr){xhr.abort();return;} if(!jobId)return;
    $('video-cancel').disabled=true;
    try{await api(encodeURIComponent(jobId)+'/cancel',{method:'POST'});await poll();}catch(e){error(e.message);$('video-cancel').disabled=false;}
  });
  $('video-prev').addEventListener('click',()=>{if(!polling && page>1){page--;poll(true);}});
  $('video-next').addEventListener('click',()=>{if(!polling && page<pages){page++;poll(true);}});
  $('video-retry').addEventListener('click',()=>poll(true));
  try { jobId=sessionStorage.getItem('face-video-job'); } catch(_) {}
  if(jobId)poll(true);
})();
