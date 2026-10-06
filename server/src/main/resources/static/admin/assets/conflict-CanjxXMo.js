import{D as a,B as e,G as c}from"./index-CYbJXhJN.js";async function r(n,t){if(!a(n))return!1;const i=c(n)+(t?`

`+t:"");try{return await e.confirm(i,"这一项被人改过了",{type:"warning",confirmButtonText:"载入最新",cancelButtonText:"先不覆盖",distinguishCancelAndClose:!0,customClass:"conflict-box"}),!0}catch{return!1}}async function l(n){return a(n)?(await e.alert(c(n)+`

列表已刷新，请在最新版本上再决定一次。`,"这一项被人改过了",{type:"warning",confirmButtonText:"知道了"}).catch(()=>{}),!0):!1}export{r as a,l as e};
