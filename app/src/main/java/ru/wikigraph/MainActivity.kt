package ru.wikigraph

import android.os.Bundle
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

private val Ink = Color(0xFF23364D)
private val Blue = Color(0xFF557DB8)
private val Green = Color(0xFF328A78)
private val Amber = Color(0xFFC18A42)
private val Muted = Color(0xFF758294)
data class Entry(val title:String, val description:String="", val pageId:Int=0, val wd:String="", val start:Int?=null, val end:Int?=null, val kind:String="Сущность", val url:String="https://ru.wikipedia.org") {
    val color get() = when(kind) { "Человек" -> Amber; "Событие" -> Green; "Эпоха" -> Blue; else -> Ink }
    val dates get() = if(start==null) "Даты не найдены" else if(end!=null && end!=start) year(start!!) + " — " + year(end!!) else year(start!!)
}
private fun year(y:Int)= if(y<0) (-y).toString()+" до н. э." else if(y==0) "ок. 1 до н. э." else y.toString()

class MainActivity:ComponentActivity() {
    override fun onCreate(savedInstanceState:Bundle?) { super.onCreate(savedInstanceState); setContent { App() } }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun App() {
    val context=androidx.compose.ui.platform.LocalContext.current
    val prefs=remember { context.getSharedPreferences("wg",0) }
    val chosen=remember { mutableStateListOf<Entry>().apply {
        runCatching {
            val a=org.json.JSONArray(prefs.getString("entries","[]"))
            for(i in 0 until a.length()) { val o=a.getJSONObject(i); add(Entry(o.optString("title"),o.optString("description"),o.optInt("pageId"),o.optString("wd"),if(o.isNull("start")) null else o.optInt("start"),if(o.isNull("end")) null else o.optInt("end"),o.optString("kind","Сущность"),o.optString("url"))) }
        }
    } }
    var query by remember { mutableStateOf("") }; var results by remember { mutableStateOf(listOf<Entry>()) }
    var screen by remember { mutableStateOf("timeline") }
    var filter by remember { mutableStateOf("Все") }; var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }; var detail by remember { mutableStateOf<Entry?>(null) }
    val scope= rememberCoroutineScope()
    fun save() { val a=org.json.JSONArray(); chosen.forEach { e -> a.put(JSONObject().apply { put("title",e.title);put("description",e.description);put("pageId",e.pageId);put("wd",e.wd);put("start",e.start?:JSONObject.NULL);put("end",e.end?:JSONObject.NULL);put("kind",e.kind);put("url",e.url) }) }; prefs.edit().putString("entries",a.toString()).apply() }
    fun search() { if(query.isBlank()) return; scope.launch { busy=true; message=""; try { results=withContext(Dispatchers.IO){ Wiki.search(query.trim()) }; if(results.isEmpty()) message="Ничего не найдено" } catch(e:Exception) { message="Ошибка загрузки. Проверьте интернет." } finally { busy=false } } }
    MaterialTheme(colorScheme=lightColorScheme(primary=Ink,secondary=Green,background=Color(0xFFF7F8FA),surface=Color.White)) {
        Scaffold(topBar={ TopAppBar(title={Column { Text("WikiGraph",fontWeight=FontWeight.Bold,color=Ink); Text("История на одной шкале",fontSize=12.sp,color=Muted) }},actions={Text(chosen.size.toString(),color=Muted,modifier=Modifier.padding(end=18.dp))},colors=TopAppBarDefaults.topAppBarColors(containerColor=Color(0xFFF7F8FA))) },
            bottomBar={ NavigationBar(containerColor=Color.White) { NavigationBarItem(selected=screen=="timeline",onClick={screen="timeline"},icon={Icon(Icons.Default.Timeline,null)},label={Text("Шкала")}); NavigationBarItem(selected=screen=="search",onClick={screen="search"},icon={Icon(Icons.Default.Search,null)},label={Text("Поиск статей")}) } }) { pad ->
            if(detail!=null) {
                Column(Modifier.fillMaxSize().padding(pad).padding(18.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                    Text("← Назад",Modifier.clickable { detail=null },color=Blue)
                    Text(detail!!.title,fontSize=26.sp,fontWeight=FontWeight.Bold,color=Ink)
                    Text(detail!!.kind.uppercase(Locale.getDefault()),color=detail!!.color)
                    Text(detail!!.dates,fontSize=22.sp,fontWeight=FontWeight.SemiBold)
                    Text(detail!!.description,color=Muted)
                    Button(onClick={runCatching { context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(detail!!.url))) }},modifier=Modifier.fillMaxWidth()){Text("Открыть статью в Википедии")}
                    OutlinedButton(onClick={chosen.removeAll{it.pageId==detail!!.pageId};save();detail=null},modifier=Modifier.fillMaxWidth()){Text("Убрать со шкалы")}
                }
            } else if(screen=="search") {
                LazyColumn(Modifier.fillMaxSize().padding(pad),contentPadding=PaddingValues(16.dp,8.dp,16.dp,24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    item {
                        OutlinedTextField(value=query,onValueChange={query=it},modifier=Modifier.fillMaxWidth(),placeholder={Text("Найти в Википедии…")},leadingIcon={Icon(Icons.Default.Search,null)},trailingIcon={if(busy) CircularProgressIndicator(Modifier.size(22.dp),strokeWidth=2.dp) else IconButton(onClick={search()}){Icon(Icons.Default.Search,"Искать")}},singleLine=true,keyboardOptions=KeyboardOptions(imeAction=ImeAction.Search),keyboardActions=KeyboardActions(onSearch={search()}),shape=RoundedCornerShape(16.dp))
                        if(message.isNotBlank()) Text(message,color=Muted,fontSize=12.sp,modifier=Modifier.padding(top=6.dp))
                    }
                    if(results.isNotEmpty()) {
                        item { Label("РЕЗУЛЬТАТЫ ПОИСКА",results.size.toString()) }
                        items(results,key={ "r"+it.pageId }) { e ->
                            Card(colors=CardDefaults.cardColors(containerColor=Color.White),shape=RoundedCornerShape(14.dp)) {
                                Row(Modifier.fillMaxWidth().padding(12.dp),verticalAlignment=Alignment.CenterVertically) {
                                    Box(Modifier.size(10.dp).clip(CircleShape).background(e.color))
                                    Column(Modifier.weight(1f).padding(horizontal=12.dp)) {
                                        Text(e.title,fontWeight=FontWeight.SemiBold,color=Ink)
                                        if(e.description.isNotBlank()) Text(e.description,color=Muted,fontSize=12.sp,maxLines=2)
                                    }
                                    IconButton(onClick={
                                        if(chosen.any{it.pageId==e.pageId}) message="Уже добавлено"
                                        else scope.launch {
                                            busy=true; message="Загружаем даты из Викиданных…"
                                            try { val full=withContext(Dispatchers.IO){Wiki.enrich(e)}; chosen.add(full); save(); message="Добавлено на шкалу" }
                                            catch(ex:Exception) { chosen.add(e); save(); message="Статья добавлена, но даты загрузить не удалось" }
                                            finally { busy=false }
                                        }
                                    }) { Icon(Icons.Default.Add,"Добавить",tint=Blue) }
                                }
                            }
                        }
                    }
                    item { Text("Выбранные статьи · ${chosen.size}",fontSize=18.sp,fontWeight=FontWeight.Bold,color=Ink) }
                    if(chosen.isEmpty()) item { Text("Добавляй статьи через поиск — даты появятся на общей шкале.",color=Muted,fontSize=13.sp) }
                    items(chosen.toList(),key={ "selected-"+it.pageId }) { e ->
                        Card(colors=CardDefaults.cardColors(containerColor=Color.White),shape=RoundedCornerShape(12.dp)) {
                            Row(Modifier.fillMaxWidth().padding(12.dp),verticalAlignment=Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) { Text(e.title,fontWeight=FontWeight.SemiBold,color=Ink); Text(e.dates,fontSize=12.sp,color=Muted) }
                                IconButton(onClick={chosen.remove(e);save()}) { Icon(Icons.Default.DeleteOutline,"Удалить из набора",tint=Muted) }
                            }
                        }
                    }
                }
            } else LazyColumn(Modifier.fillMaxSize().padding(pad),contentPadding=PaddingValues(16.dp,8.dp,16.dp,24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                item { Row(verticalAlignment=Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("Моя шкала",fontSize=22.sp,fontWeight=FontWeight.Bold,color=Ink); Text(chosen.size.toString()+" сущностей",fontSize=12.sp,color=Muted) }; Icon(Icons.Default.Timeline,null,tint=Blue) } }
                item { Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(7.dp)) { listOf("Все","Человек","Событие","Эпоха","Сущность").forEach { f -> FilterChip(selected=filter==f,onClick={filter=f},label={Text(f)}) } } }
                val visible=chosen.filter{filter=="Все"||it.kind==filter}.sortedBy{it.start?:Int.MAX_VALUE}
                if(visible.isEmpty()) item { Column(Modifier.fillMaxWidth().padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally) { Icon(Icons.Default.Timeline,null,tint=Muted,modifier=Modifier.size(38.dp)); Text("Временная шкала пока пуста",color=Muted); Text("Откройте «Поиск статей» и добавьте нужные статьи",fontSize=12.sp,color=Muted) } }
                else {
                    item { Overview(visible) }
                    items(visible,key={"s"+it.pageId}) { e ->
                        Card(Modifier.fillMaxWidth().clickable{detail=e},colors=CardDefaults.cardColors(containerColor=Color.White),shape=RoundedCornerShape(15.dp)) {
                            Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically) {
                                Column(Modifier.width(75.dp),horizontalAlignment=Alignment.CenterHorizontally) { Text(e.start?.let{year(it)}?:"—",fontSize=11.sp,color=Ink,fontWeight=FontWeight.SemiBold); Box(Modifier.padding(top=6.dp).width(3.dp).height(22.dp).background(e.color)) ; if(e.end!=null&&e.end!=e.start) Text(year(e.end),fontSize=10.sp,color=Muted) }
                                Column(Modifier.weight(1f).padding(horizontal=8.dp)) { Text(e.title,fontWeight=FontWeight.SemiBold,color=Ink); Text(e.kind+" · "+e.dates,fontSize=11.sp,color=Muted); if(e.description.isNotBlank()) Text(e.description,fontSize=12.sp,color=Muted,maxLines=2) }
                                IconButton(onClick={chosen.remove(e);save()},modifier=Modifier.size(32.dp)){Icon(Icons.Default.DeleteOutline,"Убрать",tint=Muted)}
                            }
                        }
                    }
                }
            }
        }
    }
}
@Composable private fun Label(title:String,count:String){Row(verticalAlignment=Alignment.CenterVertically){Text(title,color=Muted,fontSize=11.sp,fontWeight=FontWeight.Bold,letterSpacing=1.sp);Spacer(Modifier.width(8.dp));Text(count,color=Muted,fontSize=11.sp)}}
@Composable private fun Overview(entries:List<Entry>) {
    val years=entries.mapNotNull{it.start}; val lo=years.minOrNull(); val hi=entries.mapNotNull{it.end?:it.start}.maxOrNull()
    Card(colors=CardDefaults.cardColors(containerColor=Color.White),shape=RoundedCornerShape(14.dp)){Column(Modifier.fillMaxWidth().padding(14.dp)) {
        Text("ОБЗОР ПЕРИОДА",fontSize=10.sp,color=Muted,fontWeight=FontWeight.Bold)
        if(lo!=null&&hi!=null) { Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(year(lo),fontSize=12.sp,color=Ink);Text(year(hi),fontSize=12.sp,color=Ink)}
            Canvas(Modifier.fillMaxWidth().height(24.dp)){val span=(hi-lo).coerceAtLeast(1).toFloat();drawLine(Color.LightGray,androidx.compose.ui.geometry.Offset(0f,size.height/2),androidx.compose.ui.geometry.Offset(size.width,size.height/2),strokeWidth=3.dp.toPx());entries.forEach{e->val x=(((e.start?:lo)-lo)/span*size.width).coerceIn(0f,size.width);drawCircle(e.color,4.dp.toPx(),androidx.compose.ui.geometry.Offset(x,size.height/2));if(e.end!=null&&e.end>(e.start?:e.end)){val x2=((e.end-lo)/span*size.width).coerceIn(x,size.width);drawLine(e.color,androidx.compose.ui.geometry.Offset(x,size.height/2),androidx.compose.ui.geometry.Offset(x2,size.height/2),strokeWidth=5.dp.toPx())}}}
        } else Text("Для этих сущностей даты пока неизвестны",fontSize=12.sp,color=Muted)
    }}
}
private object Wiki {
    private fun json(url:String):JSONObject { val c=URL(url).openConnection() as HttpURLConnection;c.connectTimeout=12000;c.readTimeout=12000;c.setRequestProperty("User-Agent","WikiGraphAndroid/0.1");return try{JSONObject(c.inputStream.bufferedReader().use{it.readText()})}finally{c.disconnect()} }
    fun search(q:String):List<Entry> { val s=URLEncoder.encode(q,"UTF-8");val a=json("https://ru.wikipedia.org/w/api.php?action=query&list=search&srsearch="+s+"&srnamespace=0&srlimit=12&format=json&utf8=1").getJSONObject("query").getJSONArray("search");return (0 until a.length()).map{i->val o=a.getJSONObject(i);val title=o.getString("title");Entry(title,android.text.Html.fromHtml(o.optString("snippet").replace(Regex("<.*?>"),""),android.text.Html.FROM_HTML_MODE_COMPACT).toString(),o.getInt("pageid"),url="https://ru.wikipedia.org/wiki/"+URLEncoder.encode(title.replace(' ','_'),"UTF-8").replace("+","%20"))} }
    fun enrich(e:Entry):Entry { val q=URLEncoder.encode(e.title,"UTF-8");val pages=json("https://ru.wikipedia.org/w/api.php?action=query&prop=pageprops&ppprop=wikibase_item&titles="+q+"&format=json").getJSONObject("query").getJSONObject("pages");val key=pages.keys().asSequence().firstOrNull()?:return e;val wd=pages.getJSONObject(key).optJSONObject("pageprops")?.optString("wikibase_item").orEmpty();if(wd.isBlank()||wd=="null")return e;val entity=json("https://www.wikidata.org/wiki/Special:EntityData/"+wd+".json").getJSONObject("entities").getJSONObject(wd);val claims=entity.optJSONObject("claims")?:return e.copy(wd=wd)
        fun yr(p:String):Int? {val a=claims.optJSONArray(p)?:return null;if(a.length()==0)return null;val value=a.getJSONObject(0).optJSONObject("mainsnak")?.optJSONObject("datavalue")?.optJSONObject("value")?.optString("time")?:return null;return Regex("^([+-]?\\d{1,16})").find(value)?.groupValues?.get(1)?.toIntOrNull()}
        val birth=yr("P569");val death=yr("P570");val start=yr("P580")?:yr("P571")?:yr("P585")?:birth;val end=yr("P582")?:yr("P576")?:death
        val kind=when{birth!=null||death!=null->"Человек";yr("P580")!=null||yr("P582")!=null||yr("P571")!=null->"Событие";else->"Сущность"}
        val desc=entity.optJSONObject("descriptions")?.optJSONObject("ru")?.optString("value").orEmpty().ifBlank{e.description}
        return e.copy(description=desc,wd=wd,start=start,end=end,kind=kind)
    }
}
