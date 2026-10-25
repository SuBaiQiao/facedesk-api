'use strict';
// 历史工作台读取持久化快照；查询、详情和导出共用已应用筛选，不跟随尚未提交的表单变化。
(() => {
  const $=id=>document.getElementById(id), base=new URL('admin/history',new URL('./',location.href));
  const escape=value=>String(value??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const names={MATCHED:'已匹配',UNKNOWN:'未匹配',TOO_SMALL:'人脸过小',NO_FACE:'未检测到人脸',ERROR:'识别异常',PROCESSING:'任务处理中',COMPLETED:'任务完成',CANCELLED:'任务已取消'};
  const taskNames={RUNNING:'处理中',COMPLETED:'已完成',CANCELLED:'已取消',FAILED:'失败',INTERRUPTED:'重启中断',STOPPED:'已停止'};
  const sourceNames={IMAGE:'照片',VIDEO:'视频',USB:'USB 摄像头',RTSP:'RTSP 摄像头'};
  $('history-source').append(new Option('USB 摄像头','USB'),new Option('RTSP 摄像头','RTSP'));
  $('history-task-scope').querySelector('span').textContent='正在查看同一任务或会话的记录';
  $('history-related').textContent='查看本次任务或会话全部记录';
  $('view-history').querySelector('.page-heading p').textContent='回溯照片、视频与实时摄像头识别，保留每一次匹配的现场与结果。';
  let page=1,pages=1,request=0,selectedTask=null,selectedSource='VIDEO',applied=new URLSearchParams(),exporting=false;
  const localTime=value=>value?new Date(value).toLocaleString('zh-CN',{hour12:false}):'—';
  const score=value=>value==null?'—':value.toFixed(3);
  function error(message){$('history-error').textContent=message||'';$('history-error').hidden=!message;}
  async function fetchJson(url){const controller=new AbortController(),timer=setTimeout(()=>controller.abort(),20000);try{const r=await fetch(url,{cache:'no-store',signal:controller.signal});const data=await r.json();if(!r.ok||String(data.code)!=='200')throw new Error(data.msg||'无法读取识别历史');return data.data;}finally{clearTimeout(timer);}}
  // 页面输入是本地时间，转ISO时间传给后端；结束时间是排他边界。
  function filters(){
    const values=new URLSearchParams();
    for(const [key,id] of [['source','history-source'],['status','history-status'],['q','history-search'],['taskId','history-task']])if($(id).value.trim())values.set(key,$(id).value.trim());
    for(const key of ['from','to'])if($('history-'+key).value){const date=new Date($('history-'+key).value);if(!Number.isFinite(date.getTime()))throw new Error('请选择有效的时间');values.set(key,date.toISOString());}
    if(values.get('from')&&values.get('to')&&values.get('from')>=values.get('to'))throw new Error('开始时间必须早于结束时间');return values;
  }
  // 请求序号只允许最新查询更新表格，快速翻页或改筛选时丢弃过期响应。
  async function load(){
    const token=++request,params=new URLSearchParams(applied);params.set('page',page);params.set('size','20');
    error('');$('history-loading').hidden=false;$('history-rows').setAttribute('aria-busy','true');$('history-rows').replaceChildren();$('history-empty').hidden=true;$('history-prev').disabled=true;$('history-next').disabled=true;$('history-export').disabled=true;
    $('history-summary').textContent='正在查询…';for(const key of ['total','matched','unmatched','errors'])$('history-'+key).textContent='—';
    try{
      const url=new URL(base);url.search=params;const result=await fetchJson(url);if(token!==request)return;
      page=result.page;pages=result.pages;
      $('history-export').disabled=!result.total||exporting;
      for(const key of ['total','matched','unmatched','errors'])$('history-'+key).textContent=result[key].toLocaleString();
      $('history-summary').textContent=`共 ${result.total.toLocaleString()} 条 · 每页 20 条`;$('history-page').textContent=`${page} / ${pages}`;
      $('history-prev').disabled=page<=1;$('history-next').disabled=page>=pages;$('history-empty').hidden=Boolean(result.items.length);
      $('history-task-scope').hidden=!applied.get('taskId');const rows=$('history-rows');rows.replaceChildren();
      for(const item of result.items){
        const row=document.createElement('tr'),name=item.kind==='TASK'?'视频任务概览':item.name||item.code||'未匹配人员';
        row.innerHTML=`<td class="history-date">${escape(localTime(item.createdAt))}${item.timeSeconds!=null?`<small>视频 ${item.timeSeconds.toFixed(2)} 秒 · 第 ${item.frame+1} 帧</small>`:''}</td><td><span class="history-source-tag">${item.source==='VIDEO'?'视频':'照片'}</span><small class="history-source-name" title="${escape(item.sourceName)}">${escape(item.sourceName)}</small></td><td><div class="history-thumb">${item.thumbnail?'<img alt="识别时的人脸抓拍" loading="lazy">':'<span>—</span>'}</div></td><td><strong>${escape(name)}</strong><small>${escape(item.kind==='TASK'?taskNames[item.taskState]||item.taskState:item.code||'无人员编号')}</small></td><td><span class="history-status ${item.status==='MATCHED'?'success':item.status==='ERROR'?'error':'neutral'}">${escape(names[item.status]||item.status)}</span></td><td class="history-score">${score(item.score)}</td><td><button class="btn history-detail-button" type="button">详情</button></td>`;
        if(item.thumbnail)row.querySelector('img').src=item.thumbnail;
        row.querySelector('.history-source-tag').textContent=sourceNames[item.source]||item.source;
        if(['USB','RTSP'].includes(item.source)){
          if(item.kind==='TASK')row.querySelector('strong').textContent='摄像头会话概览';
          const when=row.querySelector('.history-date small');if(when)when.textContent=`连接后 ${item.timeSeconds.toFixed(2)} 秒 · 第 ${item.frame+1} 次识别`;
          if(item.kind!=='TASK'&&item.taskState!=='STOPPED'){const note=document.createElement('small');note.textContent='采集会话'+(taskNames[item.taskState]||item.taskState);row.children[4].appendChild(note);}
        }
        if(item.source==='VIDEO'&&item.kind!=='TASK'&&item.taskState&&item.taskState!=='COMPLETED'){
          const note=document.createElement('small');note.textContent='视频任务'+(taskNames[item.taskState]||item.taskState);row.children[4].appendChild(note);
        }
        row.querySelector('button').addEventListener('click',()=>details(item.id));rows.appendChild(row);
      }
    }catch(e){if(token===request){error(e.name==='AbortError'?'读取历史超时，请重试。':e.message);$('history-summary').textContent='读取失败，请刷新重试';}}
    finally{if(token===request){$('history-loading').hidden=true;$('history-rows').setAttribute('aria-busy','false');}}
  }
  async function details(id){
    try{
      const item=await fetchJson(new URL(base.pathname+'/'+encodeURIComponent(id),base));selectedTask=item.taskId;selectedSource=item.source;
      const image=$('history-detail-image');image.hidden=!item.thumbnail;$('history-detail-no-photo').hidden=Boolean(item.thumbnail);if(item.thumbnail)image.src=item.thumbnail;else image.removeAttribute('src');
      const fields=$('history-detail-fields');fields.replaceChildren();
      const values=[['记录编号',item.id],['识别时间',localTime(item.createdAt)],['来源',item.source==='VIDEO'?'视频识别':'照片识别'],['文件 / 来源',item.sourceName],['记录类型',item.kind==='TASK'?'视频任务概览':'人脸识别'],['识别结果',names[item.status]||item.status],['姓名',item.name],['人员编号',item.code],['人员角色',item.role],['相似度',score(item.score)],['识别阈值',score(item.threshold)],['视频任务状态',taskNames[item.taskState]||item.taskState],['视频时间点',item.timeSeconds==null?null:item.timeSeconds.toFixed(3)+' 秒'],['视频帧号',item.frame==null?null:item.frame+1],['识别耗时',item.durationMs==null?null:item.durationMs+' ms'],['检测框',item.box?`${item.box.left}, ${item.box.top}, ${item.box.right}, ${item.box.bottom}`:null],['异常信息',item.error||item.taskError],['视频任务 ID',item.taskId],['模型指纹',item.featureSpaceId]];
      for(const pair of values){if(pair[0]==='来源')pair[1]=sourceNames[item.source]||item.source;if(pair[0]==='视频任务状态')pair[0]='任务 / 会话状态';if(['USB','RTSP'].includes(item.source)){if(pair[0]==='记录类型')pair[1]=item.kind==='TASK'?'摄像头会话概览':'实时人脸识别';if(pair[0]==='视频时间点')pair[0]='连接后时间';if(pair[0]==='视频帧号')pair[0]='识别画面序号';if(pair[0]==='视频任务 ID')pair[0]='摄像头会话 ID';}}
      for(const [label,value]of values)if(value!=null&&value!==''){const div=document.createElement('div'),dt=document.createElement('dt'),dd=document.createElement('dd');dt.textContent=label;dd.textContent=value;div.append(dt,dd);fields.appendChild(div);}
      $('history-related').hidden=!item.taskId;if(!$('history-dialog').open)$('history-dialog').showModal();
    }catch(e){error(e.message||'读取详情失败');}
  }
  function apply(){try{applied=filters();page=1;load();}catch(e){error(e.message);}}
  $('history-filters').addEventListener('submit',event=>{event.preventDefault();apply();});
  $('history-refresh').addEventListener('click',()=>load());
  $('history-reset').addEventListener('click',()=>{$('history-filters').reset();$('history-task').value='';apply();});
  $('history-clear-task').addEventListener('click',()=>{$('history-task').value='';apply();});
  $('history-prev').addEventListener('click',()=>{if(page>1){page--;load();}});
  $('history-next').addEventListener('click',()=>{if(page<pages){page++;load();}});
  $('history-related').addEventListener('click',()=>{$('history-dialog').close();$('history-filters').reset();$('history-task').value=selectedTask;$('history-source').value=selectedSource;apply();});
  // 使用查询已应用的筛选导出，下载后释放对象URL，避免反复导出累积浏览器内存。
  $('history-export').addEventListener('click',async()=>{
    if(exporting)return;exporting=true;$('history-export').disabled=true;error('');
    const controller=new AbortController(),timer=setTimeout(()=>controller.abort(),60000);
    try{
      const url=new URL(base.pathname+'/export',base);url.search=new URLSearchParams(applied);const response=await fetch(url,{cache:'no-store',signal:controller.signal});
      if(!response.ok){const data=await response.json();throw new Error(data.msg||'导出失败');}
      const blob=await response.blob(),link=document.createElement('a'),objectUrl=URL.createObjectURL(blob);link.href=objectUrl;link.download='识别历史.csv';link.click();setTimeout(()=>URL.revokeObjectURL(objectUrl),30000);
    }catch(e){error(e.name==='AbortError'?'导出超时，请缩小筛选范围后重试。':e.message);}finally{clearTimeout(timer);exporting=false;$('history-export').disabled=false;}
  });
  window.addEventListener('hashchange',()=>{if(location.hash==='#history')load();});if(location.hash==='#history')load();
  window.addEventListener('open-history-record',event=>details(event.detail));
})();
