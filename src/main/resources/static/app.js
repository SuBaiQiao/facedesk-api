'use strict';
// 管理台入口：维护路由、引擎状态和共享录入弹窗；视频、实时、历史及详情通过自定义事件协作。
(() => {
  const $ = id => document.getElementById(id);
  const base = new URL('./', window.location.href);
  const roleNames = {Police_Prison:'监所民警',Police_Case:'办案民警',Lawyer:'律师',Prisoner:'在押人员',Prisoner_Inout:'进出人员',Guest:'访客',Family:'家属'};
  const stateNames = {RUNNING:'运行中',PAUSING:'正在暂停',PAUSED:'已暂停',RELOADING:'正在重载',FAILED:'引擎异常'};
  const state = {status:null,online:false,rolesReady:false,route:'faces',page:1,items:[],editing:null,profileFile:null,verifyFile:null,operation:null,profileSaving:false,verifying:false};
  let listRequest = 0, searchTimer, toastTimer, statusBusy = false, profileURL, verifyURL, confirmAction, captureEnrollment = null;
  const escape = value => String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const icon = name => `<svg aria-hidden="true"><use href="#i-${name}"/></svg>`;
  const roleLabel = role => roleNames[role] || role;
  const imageURL = person => new URL('admin/faces/image?' + new URLSearchParams({role:person.role,code:person.code}), base).href;
  function showError(id, message) { $(id).textContent = message || ''; $(id).hidden = !message; }
  function toast(message) { clearTimeout(toastTimer); $('toast').querySelector('span').textContent = message; $('toast').hidden = false; toastTimer = setTimeout(() => $('toast').hidden = true, 4200); }
  async function api(path, options = {}) {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 75000);
    try {
      const response = await fetch(new URL(path, base), {...options,cache:'no-store',signal:controller.signal,headers:{'X-Face-Admin':'1',...(options.headers || {})}});
      let result;
      try { result = await response.json(); } catch (_) { throw new Error(`服务响应异常（HTTP ${response.status}），请刷新后重试。`); }
      if (!response.ok || String(result.code) !== '200') throw new Error(result.msg || `请求失败（HTTP ${response.status}）`);
      return result.data;
    } catch (error) {
      if (error.name === 'AbortError') throw new Error('请求等待超时，请检查引擎状态后重试。');
      if (error instanceof TypeError) throw new Error('无法连接服务，请确认服务正在运行。');
      throw error;
    } finally { clearTimeout(timer); }
  }
  function formatTime(value) { return value ? new Date(value).toLocaleString('zh-CN',{hour12:false}) : '—'; }
  // 只切换本地视图，人员详情参数留在hash中；异步页面各自校验请求代次防止旧响应覆盖。
  function route() {
    const requested = location.hash.slice(1);
    if (requested === 'main') { $('main').focus(); return; }
    const routeName=requested.split('?')[0];
    state.route = ['faces','engine','verify','video','history','live','person'].includes(routeName) ? routeName : 'faces';
    for (const name of ['faces','engine','verify','video','history','live','person']) $('view-' + name).hidden = name !== state.route;
    document.querySelectorAll('[data-route]').forEach(link => {
      const active = link.dataset.route === state.route||(state.route==='person'&&link.dataset.route==='faces'); link.classList.toggle('active',active);
      if (active) link.setAttribute('aria-current','page'); else link.removeAttribute('aria-current');
    });
    $('breadcrumb-name').textContent = {faces:'人脸档案',engine:'引擎控制',verify:'识别验证',video:'视频识别',history:'识别历史',live:'实时摄像头识别',person:'人员档案详情'}[state.route];
    document.title = $('breadcrumb-name').textContent + ' · FaceDesk';
    window.scrollTo(0,0);
    if (state.route === 'faces') loadFaces();
    if (state.route === 'person') window.dispatchEvent(new Event('face-person-open'));
    $('main').focus({preventScroll:true});
  }
  function updateStatus(status) {
    state.status = status; state.online = true;
    document.body.dataset.engine = status.state;
    $('connection-error').hidden = true;
    const name = stateNames[status.state] || status.state;
    $('hero-engine-state').textContent = name.startsWith('引擎') ? name : '引擎' + name;
    const tone = status.state === 'RUNNING' ? 'success' : status.state === 'FAILED' ? 'error' : 'warning';
    $('connection').className = 'badge ' + tone; $('connection').innerHTML = '<i></i>' + escape(name);
    $('nav-count').textContent = status.databaseSize.toLocaleString(); $('stat-total').textContent = status.databaseSize.toLocaleString();
    $('stat-state').textContent = name; $('stat-active').textContent = status.activeTasks; $('stat-queued').textContent = status.queuedTasks;
    $('engine-state-badge').className = 'badge ' + tone; $('engine-state-badge').textContent = name;
    const copy = {
      RUNNING:['引擎已就绪，可以开始识别','本地模型已加载，正在接收人脸采集与识别任务。'],
      PAUSING:['正在等待当前任务结束','新的识别请求已停止接收，任务完成后进入暂停状态。'],
      PAUSED:['识别已暂停，档案仍可管理','恢复后即可继续采集与识别。暂停期间保留已加载的模型。'],
      RELOADING:['正在切换引擎状态','正在等待已有任务完成并准备模型，请稍候。'],
      FAILED:['引擎暂不可用','请检查下方错误信息和本地模型文件，修复后重试恢复或重载。']
    }[status.state] || ['未知状态','请刷新服务状态。'];
    $('engine-state-title').textContent = copy[0]; $('engine-state-description').textContent = copy[1];
    showError('engine-last-error',status.lastError);
    $('engine-active').textContent = status.activeTasks; $('engine-queued').textContent = status.queuedTasks;
    $('worker-count').textContent = `${Math.min(status.activeTasks,status.workers)} / ${status.workers}`;
    $('worker-load').style.width = `${Math.min(100,status.activeTasks / status.workers * 100)}%`;
    $('engine-limit').textContent = status.maxInFlight + ' 个'; $('engine-completed').textContent = status.completedTasks.toLocaleString() + ' 个';
    $('engine-people').textContent = status.databaseSize.toLocaleString() + ' 人';
    $('engine-threshold').textContent = Number(status.threshold).toFixed(2); $('engine-margin').textContent = Number(status.minMargin).toFixed(2);
    document.querySelectorAll('[data-engine-label]').forEach(el => el.textContent = status.recognitionBackend === 'auraface' ? 'YuNet + AuraFace' : 'YuNet + SFace');
    document.querySelectorAll('[data-recognizer-label]').forEach(el => el.textContent = status.recognitionBackend === 'auraface' ? 'AuraFace' : 'SFace');
    $('hero-dimensions').textContent = (status.featureDimensions || 128) + 'D 特征识别';
    $('engine-min-size').textContent = status.minFaceSize + ' px'; $('detector-model').textContent = status.detectorModel;
    $('recognizer-model').textContent = status.recognizerModel; $('database-path').textContent = status.databasePath;
    $('feature-space').textContent = status.featureSpaceId; $('engine-started').textContent = formatTime(status.startedAt);
    $('engine-changed').textContent = formatTime(status.changedAt);
    const busy = Boolean(state.operation) || ['PAUSING','RELOADING'].includes(status.state);
    const paused = status.state !== 'RUNNING';
    $('toggle-engine').innerHTML = icon(paused ? 'play' : 'pause') + `<span>${state.operation ? '正在处理…' : paused ? '恢复识别' : '暂停识别'}</span>`;
    $('toggle-engine').disabled = busy; $('reload-engine').disabled = busy;
    for (const id of ['add-face','empty-add','verify-submit']) {
      $(id).disabled = !status.available || (id === 'verify-submit' ? state.verifying : !state.rolesReady);
      $(id).title = status.available ? '' : '请先在引擎控制中恢复识别';
    }
    $('last-sync').textContent = '状态更新于 ' + new Date().toLocaleTimeString('zh-CN',{hour12:false});
  }
  async function refreshStatus() {
    if (statusBusy) return;
    statusBusy = true;
    try { updateStatus(await api('admin/status')); }
    catch (error) {
      document.body.dataset.engine = 'DISCONNECTED';
      $('hero-engine-state').textContent = '引擎未连接';
      state.online = false; $('connection').className = 'badge error'; $('connection').innerHTML = '<i></i>服务未连接';
      $('stat-state').textContent = '未连接';
      $('engine-state-badge').className = 'badge error'; $('engine-state-badge').textContent = '未连接';
      $('engine-state-title').textContent = '无法连接本地服务';
      $('engine-state-description').textContent = '请确认服务正在运行，然后重新连接。';
      $('connection-error-text').textContent = error.message; $('connection-error').hidden = false;
      for (const id of ['toggle-engine','reload-engine','add-face','empty-add','verify-submit']) $(id).disabled = true;
      $('last-sync').textContent = '连接失败，等待重试';
    } finally { statusBusy = false; }
  }
  async function loadRoles() {
    try {
      const roles = await api('admin/roles');
      for (const id of ['role-filter','profile-role','verify-role','video-role','live-role']) {
        const select = $(id);
        for (const role of roles) { const option = document.createElement('option'); option.value = role; option.textContent = roleLabel(role); select.appendChild(option); }
      }
      $('profile-role').value = 'Guest';
      for (const role of roles) {
        const button = document.createElement('button');
        button.type = 'button'; button.className = 'group-button'; button.dataset.role = role;
        button.setAttribute('aria-pressed','false'); button.innerHTML = icon('users');
        button.appendChild(document.createTextNode(roleLabel(role)));
        $('group-filters').appendChild(button);
      }
      state.rolesReady = true; if (state.status) updateStatus(state.status);
    } catch (error) { showError('face-list-error',error.message); }
  }
  async function loadFaces() {
    document.querySelectorAll('.group-button').forEach(button => {
      const active = button.dataset.role === $('role-filter').value;
      button.classList.toggle('active',active); button.setAttribute('aria-pressed',String(active));
    });
    const requestId = ++listRequest;
    $('face-grid').setAttribute('aria-busy','true');
    if (!state.items.length) {
      $('face-grid').hidden = false; $('face-empty').hidden = true;
      $('face-grid').innerHTML = '<div class="skeleton"></div>'.repeat(4);
    }
    showError('face-list-error','');
    try {
      const result = await api('admin/faces?' + new URLSearchParams({role:$('role-filter').value,q:$('face-search').value,page:state.page,size:12}));
      if (requestId !== listRequest) return;
      state.items = result.items; state.page = result.page;
      $('face-grid').innerHTML = '';
      for (const person of result.items) {
        const card = document.createElement('article'); card.className = 'face-card';
        card.innerHTML = `<div class="face-photo"><svg class="photo-fallback" aria-hidden="true"><use href="#i-face"/></svg>${person.hasImage ? `<img loading="lazy" alt="${escape(person.name || person.code)}的档案照片">` : ''}<span class="role-tag">${escape(roleLabel(person.role))}</span></div><div class="face-info"><h3 title="${escape(person.name || '未填写姓名')}">${escape(person.name || '未填写姓名')}</h3><span class="face-code" title="${escape(person.code)}">${escape(person.code)}</span></div><div class="face-actions"><button type="button" class="edit">${icon('edit')}编辑</button><button type="button" class="delete">${icon('trash')}删除</button></div>`;
        const img = card.querySelector('img');
        const detail=document.createElement('a');detail.className='face-detail-link';detail.href='#person?'+new URLSearchParams({role:person.role,code:person.code});detail.innerHTML=icon('info')+'详情';card.querySelector('.face-actions').prepend(detail);
        if (img) { img.src = imageURL(person); card.querySelector('.photo-fallback').hidden = true; img.addEventListener('error',() => { img.hidden = true; card.querySelector('.photo-fallback').hidden = false; }); }
        card.querySelector('.edit').addEventListener('click',() => openProfile(person));
        card.querySelector('.delete').addEventListener('click',() => confirmDelete(person));
        $('face-grid').appendChild(card);
      }
      const filtered = $('role-filter').value || $('face-search').value.trim();
      $('face-grid').hidden = !result.items.length; $('face-empty').hidden = Boolean(result.items.length);
      $('empty-title').textContent = filtered ? '没有找到匹配的档案' : '还没有人脸档案';
      $('empty-copy').textContent = filtered ? '试试其他姓名、编号或角色筛选。' : '录入一张清晰的单人照片，建立第一份人员档案。';
      $('empty-add').hidden = Boolean(filtered);
      $('list-count').textContent = result.total.toLocaleString() + ' 人';
      $('page-summary').textContent = result.total ? `共 ${result.total.toLocaleString()} 份档案 · 每页 12 份` : '暂无档案';
      $('page-number').textContent = `${result.page} / ${result.pages}`;
      $('prev-page').disabled = result.page <= 1; $('next-page').disabled = result.page >= result.pages;
    } catch (error) {
      if (requestId !== listRequest) return;
      if (!state.items.length) $('face-grid').innerHTML = '';
      showError('face-list-error',error.message + ' 点击刷新列表可重试。'); $('page-summary').textContent = '档案加载失败';
    } finally { if (requestId === listRequest) $('face-grid').setAttribute('aria-busy','false'); }
  }
  function setPreview(kind, src) {
    const img = $(kind + '-preview'), zone = $(kind === 'profile' ? 'profile-upload' : 'verify-upload');
    img.hidden = !src; zone.classList.toggle('has-image',Boolean(src)); zone.querySelector('.upload-content').hidden = Boolean(src);
    if (src) img.src = src; else img.removeAttribute('src');
  }
  function acceptPhoto(kind, file) {
    const errorId = kind === 'profile' ? 'profile-error' : 'verify-error';
    if (!file) return;
    if (!['image/jpeg','image/png'].includes(file.type) || file.size > 10 * 1024 * 1024) {
      if (kind === 'profile') { state.profileFile = null; setPreview(kind,state.editing?.hasImage ? imageURL(state.editing) : null); }
      else { state.verifyFile = null; setPreview(kind,null); }
      $(kind + '-file').value = '';
      showError(errorId,file.size > 10 * 1024 * 1024 ? '图片不能超过 10 MB，请压缩后重试。' : '请选择 JPG 或 PNG 图片。'); return;
    }
    showError(errorId,'');
    if (kind === 'profile') { state.profileFile = file; if (profileURL) URL.revokeObjectURL(profileURL); profileURL = URL.createObjectURL(file); setPreview(kind,profileURL); }
    else { state.verifyFile = file; if (verifyURL) URL.revokeObjectURL(verifyURL); verifyURL = URL.createObjectURL(file); setPreview(kind,verifyURL); }
  }
  // 每次打开清空上一次编辑和抓拍上下文，释放旧预览URL，防止照片或保存语义串到下次录入。
  function openProfile(person = null) {
    captureEnrollment = null; state.editing = person; state.profileFile = null;
    if (profileURL) { URL.revokeObjectURL(profileURL); profileURL = null; }
    $('face-form').reset(); showError('profile-error','');
    $('face-dialog-title').textContent = person ? '编辑人脸档案' : '录入人脸';
    $('profile-name').value = person?.name || ''; $('profile-code').value = person?.code || '';
    $('profile-role').value = person?.role || 'Guest'; $('profile-code').readOnly = Boolean(person); $('profile-role').disabled = Boolean(person);
    $('profile-hint').textContent = person ? '不选择照片只改姓名；更换主照片会清空附加参考照片，请重新补充。' : '使用清晰的单人照片。同角色、同编号会替换主照片并清空附加参考照片。';
    setPreview('profile',person?.hasImage ? imageURL(person) : null);
    $('face-dialog').showModal(); $('profile-name').focus();
  }
  // 普通录入沿用采集接口；抓拍录入走只新增接口，编号冲突时保留已有档案。
  async function saveProfile(event) {
    event.preventDefault(); if (state.profileSaving) return;
    const code = $('profile-code').value.trim(), role = $('profile-role').value;
    if (!code) { showError('profile-error','请输入人员编号。'); $('profile-code').focus(); return; }
    if (!state.editing && !state.profileFile) { showError('profile-error','请先选择一张清晰的单人照片。'); return; }
    if (state.profileFile && !state.status?.available) { showError('profile-error','采集照片需要引擎运行，请先恢复识别。'); return; }
    if(state.editing&&state.profileFile&&!confirm('更换主照片会清空该人员的附加参考照片，是否继续？'))return;
    const body = new FormData(); body.append('role',role); body.append('code',code); body.append('name',$('profile-name').value.trim());
    if (state.profileFile) body.append('img',state.profileFile,state.profileFile.name);
    state.profileSaving = true;
    const buttons = $('face-form').querySelectorAll('button'); buttons.forEach(button => button.disabled = true);
    $('profile-submit').textContent = state.profileFile ? '正在提取并保存…' : '正在保存…'; showError('profile-error','');
    try {
      await api(state.editing ? 'admin/faces/update' : captureEnrollment ? 'admin/faces/from-capture' : 'face/v2/faceCollection',{method:'POST',body});
      $('face-dialog').close(); try { captureEnrollment?.onSaved?.({role,code}); } catch (_) {} captureEnrollment=null; toast('人员档案已保存'); await Promise.all([loadFaces(),refreshStatus()]);window.dispatchEvent(new Event('face-person-refresh'));
    } catch (error) { showError('profile-error',error.message); }
    finally { state.profileSaving = false; buttons.forEach(button => button.disabled = false); $('profile-submit').textContent = '保存档案'; }
  }
  function confirm(options) {
    confirmAction = options.action; $('confirm-title').textContent = options.title; $('confirm-message').textContent = options.message;
    $('confirm-submit').textContent = options.label; $('confirm-submit').className = 'btn ' + (options.danger ? 'danger' : 'primary');
    showError('confirm-error',''); $('confirm-submit').disabled = false; $('confirm-dialog').showModal();
  }
  function confirmDelete(person) {
    confirm({title:'删除这份人脸档案？',message:`${person.name || '未填写姓名'} · ${person.code}\n${roleLabel(person.role)}\n\n删除后，该人员将不再参与识别，需要重新录入才能恢复。`,label:'确认删除',danger:true,action:async () => {
      await api('admin/faces?' + new URLSearchParams({role:person.role,code:person.code}),{method:'DELETE'});
      toast('人员档案已删除'); await Promise.all([loadFaces(),refreshStatus()]);
    }});
  }
  async function engineAction(action) {
    if (state.operation) return;
    state.operation = action;
    if (state.status) updateStatus(state.status);
    try {
      const result = await api('admin/engine/' + action,{method:'POST'});
      updateStatus(result); toast(action === 'pause' ? '引擎已暂停' : action === 'resume' ? '引擎已恢复' : '模型已重载' + (result.state === 'PAUSED' ? '，仍保持暂停' : ''));
    } finally { state.operation = null; await refreshStatus(); }
  }
  async function verify(event) {
    event.preventDefault(); if (state.verifying) return;
    if (!state.verifyFile) { showError('verify-error','请先选择待识别照片。'); return; }
    state.verifying = true;
    const body = new FormData(); body.append('img',state.verifyFile,state.verifyFile.name); body.append('role',$('verify-role').value);
    $('verify-submit').disabled = true; $('verify-submit').textContent = '正在识别…'; showError('verify-error','');
    $('verify-result').className = 'result-placeholder'; $('verify-result').innerHTML = icon('scan') + '<h3>正在检索本地底库…</h3><p>正在检测、对齐并比对人脸特征。</p>';
    const started = performance.now();
    try {
      const result = await api('face/v2/faceVerification',{method:'POST',body});
      $('verify-duration').textContent = ((performance.now() - started) / 1000).toFixed(2) + ' 秒';
      $('verify-result').className = '';
      if (!result.length) {
        $('verify-result').innerHTML = `<div class="result-banner no-match">${icon('info')}<div><strong>未找到明确匹配</strong><p>没有候选满足当前阈值和分差要求</p></div></div><p class="section-note">请确认人员已经录入，并尝试光照均匀、面部清晰的照片。不会因为相似度接近就强行匹配。</p>`;
      } else {
        const person = result[0];
        $('verify-result').innerHTML = `<div class="result-banner">${icon('check')}<div><strong>找到唯一匹配人员</strong><p>已满足当前阈值和候选分差要求</p></div></div><div class="match-person"><img alt="匹配人员的档案照片"><div><h3>${escape(person.name || '未填写姓名')}</h3><p>${escape(roleLabel(person.faceRole))} · ${escape(person.code)}</p></div></div><dl class="compact-details"><div><dt>余弦相似度</dt><dd><strong class="match-score">${Number(person.score).toFixed(4)}</strong></dd></div><div><dt>最低相似度</dt><dd>${Number(state.status?.threshold || 0.55).toFixed(2)}</dd></div></dl><div class="offline-warning">此结果用于身份相似度比对，不包含活体检测。</div>`;
        const img = $('verify-result').querySelector('img'); img.src = imageURL({role:person.faceRole,code:person.code}); img.addEventListener('error',() => img.hidden = true);
      }
    } catch (error) {
      showError('verify-error',error.message); $('verify-result').className = 'result-placeholder';
      $('verify-result').innerHTML = icon('info') + '<h3>本次识别未完成</h3><p>请根据左侧提示调整后重试。</p>';
    } finally { state.verifying = false; $('verify-submit').innerHTML = icon('scan') + '开始识别'; $('verify-submit').disabled = !state.status?.available; refreshStatus(); }
  }
  $('face-form').addEventListener('submit',saveProfile); $('verify-form').addEventListener('submit',verify);
  for (const id of ['add-face','empty-add']) $(id).addEventListener('click',() => openProfile());
  $('face-search').addEventListener('input',() => { clearTimeout(searchTimer); searchTimer = setTimeout(() => { state.page = 1; loadFaces(); },280); });
  $('role-filter').addEventListener('change',() => { state.page = 1; loadFaces(); });
  $('refresh-faces').addEventListener('click',() => Promise.all([loadFaces(),refreshStatus()]));
  $('prev-page').addEventListener('click',() => { state.page--; loadFaces(); }); $('next-page').addEventListener('click',() => { state.page++; loadFaces(); });
  $('refresh-engine').addEventListener('click',refreshStatus); $('retry-connection').addEventListener('click',async () => { if (!state.rolesReady) await loadRoles(); await refreshStatus(); await loadFaces(); });
  $('toggle-engine').addEventListener('click',async () => { try { await engineAction(state.status?.state === 'RUNNING' ? 'pause' : 'resume'); } catch (error) { showError('engine-last-error',error.message); } });
  $('reload-engine').addEventListener('click',() => confirm({title:'重新加载本地模型？',message:'服务会先等待正在执行的任务结束，再重新加载本地模型。期间新的识别请求会被拒绝。\n\n模型内容须与当前底库保持一致；若内容不同，引擎会停止接收识别任务。',label:'重载模型',action:() => engineAction('reload')}));
  $('confirm-submit').addEventListener('click',async () => { $('confirm-submit').disabled = true; showError('confirm-error',''); try { await confirmAction(); $('confirm-dialog').close(); } catch (error) { showError('confirm-error',error.message); } finally { $('confirm-submit').disabled = false; } });
  document.querySelectorAll('[data-close]').forEach(button => button.addEventListener('click',() => $(button.dataset.close).close()));
  $('face-dialog').addEventListener('cancel',event => { if (state.profileSaving) event.preventDefault(); });
  $('confirm-dialog').addEventListener('cancel',event => { if ($('confirm-submit').disabled) event.preventDefault(); });
  for (const kind of ['profile','verify']) {
    const input = $(kind + '-file'), zone = $(kind === 'profile' ? 'profile-upload' : 'verify-upload');
    input.addEventListener('change',() => acceptPhoto(kind,input.files[0]));
    zone.addEventListener('dragover',event => { event.preventDefault(); zone.classList.add('dragging'); });
    zone.addEventListener('dragleave',() => zone.classList.remove('dragging'));
    zone.addEventListener('drop',event => { event.preventDefault(); zone.classList.remove('dragging'); acceptPhoto(kind,event.dataTransfer.files[0]); });
  }
  $('group-filters').addEventListener('click',event => {
    const button = event.target.closest('[data-role]');
    if (!button) return;
    $('role-filter').value = button.dataset.role; state.page = 1; loadFaces();
  });
  window.addEventListener('hashchange',route);
  window.addEventListener('face-edit-person',event=>openProfile(event.detail));
  // 将点击时的抓拍转成独立File并复用录入弹窗；摄像头继续取流也不会替换这张待保存照片。
  window.addEventListener('face-enroll-capture',event=>{
    if(state.profileSaving || $('face-dialog').open)return;
    const capture=event.detail;
    try {
      if(!state.rolesReady)throw new Error('人员角色尚未加载，请稍后重试。');
      const match=/^data:(image\/(?:jpeg|png));base64,([A-Za-z0-9+/=]+)$/.exec(capture?.image||'');
      if(!match || match[2].length>14*1024*1024)throw new Error('没有可用的录入抓拍，请重新抓拍。');
      const binary=atob(match[2]), bytes=Uint8Array.from(binary,c=>c.charCodeAt(0));
      const file=new File([bytes],'capture-'+Date.now()+(match[1]==='image/png'?'.png':'.jpg'),{type:match[1]});
      openProfile();captureEnrollment=capture;acceptPhoto('profile',file);
      if(capture.role && [...$('profile-role').options].some(o=>o.value===capture.role))$('profile-role').value=capture.role;
      $('face-dialog-title').textContent='从抓拍录入人脸';
      $('profile-hint').textContent=(capture.sourceLabel||'未匹配抓拍')+'。确认照片中的人员后填写档案；编号已存在时不会覆盖。模糊、侧脸或多人照片请重新抓拍。';
    } catch(error) { toast(error.message); }
  });
  document.addEventListener('visibilitychange',() => { if (!document.hidden) refreshStatus(); });
  for (const id of ['add-face','empty-add','verify-submit']) $(id).disabled = true;
  loadRoles(); refreshStatus(); route();
  setInterval(() => { if (!document.hidden) refreshStatus(); },5000);
})();
