const $=id=>document.getElementById(id);
const positions={audit:0,outbox:0}; const counts={audit:0,outbox:0};
async function get(path){const r=await fetch(path);if(!r.ok)throw new Error(`Request failed: ${r.status}`);return r.json();}
function table(id,rows){
 const root=$(id);root.replaceChildren();
 if(!rows.length){root.textContent='No records found.';return;}
 const t=document.createElement('table'),head=t.createTHead().insertRow();
 Object.keys(rows[0]).forEach(k=>{const th=document.createElement('th');th.textContent=k;head.append(th);});
 const body=t.createTBody();rows.forEach(row=>{const tr=body.insertRow();Object.entries(row).forEach(([k,v])=>{
   const td=tr.insertCell();if(id==='audit'&&k.toUpperCase()==='EVENT_ID'){
     const b=document.createElement('button');b.textContent=v;b.onclick=()=>show(v);td.append(b);
   }else td.textContent=v??'—';
 });});root.append(t);
}
async function show(id){try{$('detail').textContent=JSON.stringify(JSON.parse((await get('api/audit/'+encodeURIComponent(id))).envelope),null,2);}catch(e){$('error').textContent=e.message;}}
async function load(kind){try{
 const q=new URLSearchParams({offset:positions[kind],size:50});
 if(kind==='outbox'&&$('status').value)q.set('status',$('status').value);
 if(kind==='audit'&&$('aggregate').value.trim())q.set('aggregateId',$('aggregate').value.trim());
 const rows=await get('api/'+kind+'?'+q);counts[kind]=rows.length;table(kind,rows);
 $(kind+'Page').textContent=`Page ${positions[kind]/50+1}`;$('error').textContent='';
}catch(e){$('error').textContent=e.message;}}
for(const kind of ['outbox','audit']){
 const cap=kind[0].toUpperCase()+kind.slice(1);
 $('refresh'+cap).onclick=()=>{positions[kind]=0;load(kind);};
 $('prev'+cap).onclick=()=>{positions[kind]=Math.max(0,positions[kind]-50);load(kind);};
 $('next'+cap).onclick=()=>{if(counts[kind]===50){positions[kind]+=50;load(kind);}};
 load(kind);
}
