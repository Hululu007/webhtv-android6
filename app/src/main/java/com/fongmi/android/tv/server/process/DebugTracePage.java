package com.fongmi.android.tv.server.process;

import com.github.catvod.crawler.DebugEvent;
import com.github.catvod.crawler.DebugLogStore;

import java.util.List;

/**
 * Card-based debug log viewer.
 *
 * <p>The text viewer answers "what happened, in order". This one answers "which single
 * action failed, and at which step" by grouping events on their trace id: one card per
 * user action, failures first, each card carrying the conclusion above the timeline.
 *
 * <p>Data comes from {@code /debug/trace.json}; the page is plain HTML with no external
 * assets so it works on a TV-adjacent browser with no network.
 */
public class DebugTracePage {

    /**
     * @param since version the caller already has; when it still matches, only the
     *              envelope is returned. The page polls every two seconds and the event
     *              list can reach {@code MAX_EVENTS} entries, so rebuilding it
     *              unconditionally would push megabytes over the wire for nothing.
     */
    public static String json(long since) {
        long version = DebugLogStore.version();
        StringBuilder builder = new StringBuilder(160);
        builder.append("{\"enabled\":").append(DebugLogStore.isEnabled());
        builder.append(",\"version\":").append(version);
        if (since >= 0 && since == version) {
            return builder.append(",\"events\":[]}").toString();
        }
        List<DebugEvent> events = DebugLogStore.events();
        builder.append(",\"events\":[");
        for (int i = 0; i < events.size(); i++) {
            if (i > 0) builder.append(',');
            builder.append(events.get(i).toJson());
        }
        builder.append("]}");
        return builder.toString();
    }

    public static String html() {
        return "<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1,viewport-fit=cover\">"
                + "<title>爬虫日志</title><style>" + css() + "</style></head><body>"
                + "<main>"
                + "<header><h1>爬虫日志</h1><span id=\"meta\" class=\"meta\"></span>"
                + "<a href=\"/debug/logs\">原始日志</a><a href=\"/debug/clear\">清空</a>"
                + "<a id=\"toggle\" href=\"/debug/disable\">关闭</a></header>"
                + "<section class=\"tools\">"
                + "<select id=\"site\"><option value=\"\">全部站点</option></select>"
                + "<input id=\"filter\" placeholder=\"过滤关键词\">"
                + "<button id=\"onlyFail\" class=\"chip\">只看失败</button>"
                + "<button id=\"pause\" class=\"chip\">暂停</button>"
                + "</section>"
                + "<p id=\"hint\" class=\"hint\"></p>"
                + "<section id=\"cards\" class=\"cards\"></section>"
                + "</main><script>" + script() + "</script></body></html>";
    }

    private static String css() {
        return "html,body{margin:0;background:#F4F6F8;color:#1F2328;font:14px/1.5 -apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif}"
                + "main{max-width:900px;margin:0 auto;padding:10px}"
                + "header{display:flex;flex-wrap:wrap;gap:8px;align-items:center;background:#fff;border:1px solid #D3D1C7;border-radius:10px;padding:10px 12px;margin-bottom:8px}"
                + "h1{margin:0;font-size:15px;font-weight:500}"
                + ".meta{color:#5F5E5A;font-size:12px;margin-right:auto}"
                + "header a{color:#185FA5;font-size:12px;text-decoration:none;border:1px solid #D3D1C7;border-radius:6px;padding:4px 8px}"
                + ".tools{display:flex;flex-wrap:wrap;gap:6px;background:#fff;border:1px solid #D3D1C7;border-radius:10px;padding:8px;margin-bottom:8px}"
                + "select,input{box-sizing:border-box;border:1px solid #D3D1C7;border-radius:6px;padding:6px 8px;font:inherit;background:#fff;color:#1F2328}"
                + "input{flex:1;min-width:120px}"
                + ".chip{border:1px solid #D3D1C7;border-radius:6px;background:#fff;color:#1F2328;padding:6px 9px;font:inherit;font-size:12px}"
                + ".chip.on{background:#E6F1FB;border-color:#378ADD;color:#0C447C}"
                + ".hint{margin:0 0 8px;color:#5F5E5A;font-size:12px}"
                + ".cards{display:grid;gap:8px}"
                + ".card{background:#fff;border:1px solid #D3D1C7;border-left:3px solid #888780;border-radius:9px;overflow:hidden}"
                + ".card.err{border-color:#F7C1C1;border-left-color:#E24B4A}"
                + ".card.ok{border-left-color:#1D9E75}"
                + ".card.warn{border-color:#FAC775;border-left-color:#BA7517}"
                + ".head{display:flex;align-items:center;gap:6px;padding:9px 11px;cursor:pointer}"
                + ".card.err .head{background:#FCEBEB}"
                + ".card.warn .head{background:#FAEEDA}"
                + ".title{font-size:13px;font-weight:500}"
                + ".badge{font-size:10px;border-radius:3px;padding:1px 4px;background:#F1EFE8;color:#5F5E5A}"
                + ".badge.err{background:#E24B4A;color:#fff}"
                + ".badge.ok{background:#1D9E75;color:#fff}"
                + ".badge.warn{background:#BA7517;color:#fff}"
                + ".ms{margin-left:auto;font-size:11px;color:#5F5E5A;font-family:ui-monospace,Menlo,Consolas,monospace}"
                + ".conclusion{padding:0 11px 8px;font-size:12px;line-height:1.5}"
                + ".card.err .conclusion{color:#A32D2D}"
                + ".card.warn .conclusion{color:#854F0B}"
                + ".body{display:none;padding:0 11px 9px}"
                + ".card.open .body{display:block}"
                + ".row{display:flex;align-items:baseline;gap:7px;font-size:12px;padding:2px 0}"
                + ".t{color:#8C959F;font-family:ui-monospace,Menlo,Consolas,monospace;font-size:11px;flex:0 0 48px}"
                + ".k{flex:0 0 44px;color:#5F5E5A}"
                + ".v{flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-size:11px}"
                + ".v.mono{font-family:ui-monospace,Menlo,Consolas,monospace}"
                + ".c{font-size:11px;color:#0F6E56}"
                + ".c.bad{color:#A32D2D}"
                + ".n{font-size:11px;color:#8C959F;font-family:ui-monospace,Menlo,Consolas,monospace;flex:0 0 auto}"
                + ".row.bad{background:#FCEBEB;border-radius:5px;padding:4px 5px;margin:0 -5px}"
                + ".row.warn{background:#FAEEDA;border-radius:5px;padding:4px 5px;margin:0 -5px}"
                + ".copy{border:1px solid #85B7EB;border-radius:4px;background:#E6F1FB;color:#185FA5;font-size:10px;padding:1px 5px;cursor:pointer}"
                + ".stack{margin-top:4px;padding:6px 8px;background:#FCEBEB;border-radius:6px;color:#791F1F;font-family:ui-monospace,Menlo,Consolas,monospace;font-size:11px;white-space:pre-wrap;overflow-wrap:anywhere}"
                + ".empty{padding:20px;text-align:center;color:#5F5E5A;font-size:13px;background:#fff;border:1px solid #D3D1C7;border-radius:10px}"
                + "@media(max-width:520px){main{padding:8px}.k{flex-basis:38px}.t{flex-basis:44px}.ms{font-size:10px}}";
    }

    private static String script() {
        return "var all=[],onlyFail=false,paused=false,timer=null,lastVersion=-1;"
                + "var cards=document.getElementById('cards'),meta=document.getElementById('meta'),hint=document.getElementById('hint');"
                + "function esc(s){return String(s==null?'':s).replace(/[&<>\"']/g,function(c){return {'&':'&amp;','<':'&lt;','>':'&gt;','\"':'&quot;',\"'\":'&#39;'}[c]})}"
                + "function clock(ts){return ts&&ts.length>=12?ts.slice(11):ts||''}"
                + "function shortUrl(u){if(!u)return '';return u.replace(/^https?:\\/\\//,'').replace(/^http:\\/\\//,'')}"
                + "function reasonText(reason,e){"
                + " if(reason==='http-timeout')return '请求超时：'+(e.ms||'')+'ms 无响应';"
                + " if(reason==='http-status')return 'HTTP '+(e.code||'')+' 返回错误';"
                + " if(reason==='http-error')return '网络错误：'+(e.error||'')+' '+(e.msg||'');"
                + " if(reason==='js-exception')return '爬虫抛异常：'+(e.msg||e.error||'');"
                + " if(reason==='method-missing')return '爬虫没有导出方法 '+((e.extra&&e.extra.method)||e.method||'');"
                + " if(reason==='empty-result')return '返回 0 条，选择器可能没命中';"
                + " if(reason==='empty-url')return '播放地址为空';"
                + " if(reason==='parse-timeout')return '解析超时：未嗅探到播放地址';"
                + " if(reason==='parse-error')return '解析失败';"
                + " return ''}"
                + "function suggestion(reason){"
                + " if(reason==='http-timeout')return '先确认该域名可达，再考虑调大 timeout';"
                + " if(reason==='http-status')return '状态码说明接口地址或参数不对，点复制到浏览器验证';"
                + " if(reason==='js-exception')return '看下面堆栈的文件和行号';"
                + " if(reason==='method-missing')return '检查导出的方法名拼写，或该功能确实没实现';"
                + " if(reason==='empty-result')return '把响应对照选择器规则，多半是 DOM 变了';"
                + " if(reason==='empty-url')return '检查 play() 的返回值，或 flag 与线路不匹配';"
                + " if(reason==='parse-timeout')return '站点需要解析接口，或爬虫应直接返回直链';"
                + " return ''}"
                + "function group(){"
                + " var map=new Map();"
                + " for(var i=0;i<all.length;i++){var e=all[i];var key=e.trace||('s:'+(e.site||'-'));if(!map.has(key))map.set(key,[]);map.get(key).push(e)}"
                + " var list=[];map.forEach(function(evs,key){"
                + "  var site='',lang='',method='',start=0,end=0,fail=false,reason='',last=null,detail=null;"
                + "  for(var j=0;j<evs.length;j++){var e=evs[j];if(!site&&e.site)site=e.site;if(!lang&&e.lang)lang=e.lang;"
                + "   if(e.kind==='call'&&!method)method=e.method;"
                + "   if(e.kind==='stage'&&!method&&e.method)method=e.method;"
                + "   if(e.ep){if(!start)start=e.ep;end=e.ep}"
                + "   if(e.ok===false||e.error||e.reason){fail=true;last=e;reason=e.reason||reason;"
                + "    if(e.error||e.msg||e.code!=null)detail=e}}"
                + "  list.push({key:key,events:evs,site:site,lang:lang,method:method,start:start,end:end,fail:fail,reason:reason,last:detail||last})});"
                + " return list}"
                + "function render(){"
                + " var list=group();"
                + " var siteSel=document.getElementById('site');var site=siteSel.value;"
                + " var kw=(document.getElementById('filter').value||'').toLowerCase();"
                + " list=list.filter(function(c){if(site&&c.site!==site)return false;if(onlyFail&&!c.fail)return false;"
                + "  if(kw){var text=JSON.stringify(c.events).toLowerCase();if(text.indexOf(kw)<0)return false}return true});"
                + " list.sort(function(a,b){if(a.fail!==b.fail)return a.fail?-1:1;return (b.end||0)-(a.end||0)});"
                + " if(!list.length){cards.innerHTML='<p class=\"empty\">没有符合条件的记录</p>';return}"
                + " var html='';"
                + " for(var i=0;i<list.length;i++){html+=card(list[i],i)}"
                + " cards.innerHTML=html;"
                + "}"
                + "function card(c,idx){"
                + " var cls=c.fail?'err':(c.reason?'warn':'ok');"
                + " var state=c.fail?'失败':'成功';"
                + " var ms=c.end&&c.start?(c.end-c.start):0;"
                + " var title=(c.site||'未知站点')+' · '+(c.method||'其他');"
                + " var html='<div class=\"card '+cls+(idx===0?' open':'')+'\" data-i=\"'+idx+'\">';"
                + " html+='<div class=\"head\" onclick=\"toggle(this)\">';"
                + " html+='<span class=\"title\">'+esc(title)+'</span>';"
                + " if(c.lang)html+='<span class=\"badge\">'+esc(c.lang.toUpperCase())+'</span>';"
                + " html+='<span class=\"badge '+cls+'\">'+state+'</span>';"
                + " if(c.key&&c.key.indexOf('s:')!==0)html+='<span class=\"badge\">'+esc(c.key)+'</span>';"
                + " html+='<span class=\"ms\">'+(ms>=1000?(ms/1000).toFixed(1)+'s':ms+'ms')+'</span></div>';"
                + " if(c.fail){var t=reasonText(c.reason,c.last||{});var s=suggestion(c.reason);"
                + "  html+='<div class=\"conclusion\">'+esc(t||'失败')+(s?'<br><span style=\"opacity:.8\">建议：'+esc(s)+'</span>':'')+'</div>'}"
                + " html+='<div class=\"body\">';"
                + " for(var j=0;j<c.events.length;j++)html+=row(c.events[j]);"
                + " html+='</div></div>';"
                + " return html}"
                + "function row(e){"
                + " var t=clock(e.ts),k='',v='',extra='',cls='',copy='';"
                + " if(e.kind==='call'){k=e.method||'call';v=e.msg||''}"
                + " else if(e.kind==='http'){k=e.method||'GET';v=shortUrl(e.url);"
                + "  extra=e.code?(String(e.code)):(e.error?'失败':'');"
                + "  if(e.code>=400||e.error){cls='bad';if(!extra)extra='失败'}"
                + "  copy=e.url?'<button class=\"copy\" onclick=\"copyUrl(event,this)\" data-url=\"'+esc(e.url)+'\">复制</button>':''}"
                + " else if(e.kind==='console'){k='console';v=(e.method||'log')+': '+(e.msg||'');if(e.method==='error')cls='bad'}"
                + " else if(e.kind==='exception'){k='异常';v=(e.error||'')+' '+(e.msg||'');cls='bad'}"
                + " else if(e.kind==='result'){k='返回';v=(e.ok?'成功':'失败')+(e.items!=null?' · '+e.items+' 条':'');if(!e.ok)cls='bad'}"
                + " else if(e.kind==='stage'){k='阶段';v=(e.method||'')+' '+extraText(e.extra)}"
                + " else {k=e.method||'日志';v=e.msg||''}"
                + " var html='<div class=\"row '+cls+'\">';"
                + " html+='<span class=\"t\">'+esc(t)+'</span>';"
                + " html+='<span class=\"k\">'+esc(k)+'</span>';"
                + " html+='<span class=\"v mono\">'+esc(v)+'</span>';"
                + " if(extra)html+='<span class=\"c '+(cls==='bad'?'bad':'')+'\">'+esc(extra)+'</span>';"
                + " if(e.ms!=null)html+='<span class=\"n\">'+e.ms+'ms</span>';"
                + " html+=copy+'</div>';"
                + " if(e.kind==='exception'&&e.stack){html+='<div class=\"stack\">'+esc(firstLine(e.stack))+'</div>'}"
                + " return html}"
                + "function extraText(x){if(!x)return '';var out=[];for(var k in x){if(k==='url'||k==='referer'||k==='ua'||k==='cookie')continue;out.push(k+'='+x[k])}return out.join(' ')}"
                + "function firstLine(s){var lines=String(s).split('\\n');for(var i=0;i<lines.length;i++){var t=lines[i].trim();if(t.indexOf('at ')>-1)return t}return lines[0]||''}"
                + "function toggle(el){var card=el.parentNode;card.classList.toggle('open')}"
                + "function copyUrl(ev,el){ev.stopPropagation();var url=el.getAttribute('data-url');"
                + " if(navigator.clipboard&&navigator.clipboard.writeText){navigator.clipboard.writeText(url).then(function(){flash(el)},function(){fallback(url)})}"
                + " else fallback(url)}"
                + "function fallback(url){window.prompt('复制这个地址',url)}"
                + "function flash(el){var old=el.textContent;el.textContent='已复制';setTimeout(function(){el.textContent=old},1200)}"
                + "function refresh(){"
                + " if(paused)return;"
                + " fetch('/debug/trace.json?v='+lastVersion,{cache:'no-store'}).then(function(r){return r.json()}).then(function(d){"
                + "  if(d.version===lastVersion)return;lastVersion=d.version;"
                + "  all=d.events||[];"
                + "  meta.textContent=(d.enabled?'开启':'关闭')+' · '+all.length+' 个事件';"
                + "  var sel=document.getElementById('site');var cur=sel.value;var sites={};"
                + "  for(var i=0;i<all.length;i++)if(all[i].site)sites[all[i].site]=1;"
                + "  var opts='<option value=\"\">全部站点</option>';for(var s in sites)opts+='<option value=\"'+esc(s)+'\"'+(s===cur?' selected':'')+'>'+esc(s)+'</option>';"
                + "  sel.innerHTML=opts;"
                + "  render();"
                + " }).catch(function(){})}"
                + "document.getElementById('site').onchange=render;"
                + "document.getElementById('filter').oninput=render;"
                + "document.getElementById('onlyFail').onclick=function(){onlyFail=!onlyFail;this.classList.toggle('on',onlyFail);render()};"
                + "document.getElementById('pause').onclick=function(){paused=!paused;this.textContent=paused?'继续':'暂停';this.classList.toggle('on',paused)};"
                + "refresh();timer=setInterval(refresh,2000);";
    }
}
