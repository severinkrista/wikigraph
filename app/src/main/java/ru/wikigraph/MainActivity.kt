package ru.wikigraph

import android.os.Bundle
import android.content.pm.ActivityInfo
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
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
data class DateOption(val label:String,val start:Int?,val end:Int?)
data class Entry(val title:String, val description:String="", val pageId:Int=0, val wd:String="", val start:Int?=null, val end:Int?=null, val kind:String="Сущность", val url:String="https://ru.wikipedia.org", val dateOptions:List<DateOption> = emptyList(), val selectedDateOption:Int = 0) {
    val color get() = listOf(
        Color(0xFF557DB8), Color(0xFF328A78), Color(0xFFC18A42), Color(0xFF9A69B5),
        Color(0xFFD45D79), Color(0xFF458FBD), Color(0xFF7A9D38), Color(0xFFCC7045),
        Color(0xFF5B6BBF), Color(0xFF299D91), Color(0xFFB85C9E), Color(0xFF8A7658)
    )[Math.floorMod(pageId, 12)]
    val dates get() = if(start==null) "Даты не найдены" else if(end!=null && end!=start) year(start!!) + " — " + year(end!!) else year(start!!)
}
private fun serializeEntries(entries:List<Entry>):String {
    val a=org.json.JSONArray()
    entries.forEach { e -> a.put(JSONObject().apply {
        put("title",e.title); put("description",e.description); put("pageId",e.pageId); put("wd",e.wd)
        put("start",e.start?:JSONObject.NULL); put("end",e.end?:JSONObject.NULL); put("kind",e.kind); put("url",e.url)
        put("selectedDateOption",e.selectedDateOption)
        val opts=org.json.JSONArray(); e.dateOptions.forEach { d -> opts.put(JSONObject().apply { put("label",d.label); put("start",d.start?:JSONObject.NULL); put("end",d.end?:JSONObject.NULL) }) }; put("dateOptions",opts)
    }) }
    return a.toString()
}
private fun parseEntries(raw:String):List<Entry> = runCatching {
    val a=org.json.JSONArray(raw)
    (0 until a.length()).map { i ->
        val o=a.getJSONObject(i); val opts=o.optJSONArray("dateOptions")?:org.json.JSONArray()
        val dates=(0 until opts.length()).map { j -> val d=opts.getJSONObject(j); DateOption(d.optString("label"),if(d.isNull("start")) null else d.optInt("start"),if(d.isNull("end")) null else d.optInt("end")) }
        Entry(o.optString("title"),o.optString("description"),o.optInt("pageId"),o.optString("wd"),if(o.isNull("start")) null else o.optInt("start"),if(o.isNull("end")) null else o.optInt("end"),o.optString("kind","Сущность"),o.optString("url"),dates,o.optInt("selectedDateOption",0))
    }
}.getOrDefault(emptyList())
private fun year(y:Int)= if(y<0) (-y).toString()+" до н. э." else if(y==0) "ок. 1 до н. э." else y.toString()

class MainActivity:ComponentActivity() {
    override fun onCreate(savedInstanceState:Bundle?) { super.onCreate(savedInstanceState); setContent { App() } }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun App() {
    val context=androidx.compose.ui.platform.LocalContext.current
    val prefs=remember { context.getSharedPreferences("wg",0) }
    val chosen=remember { mutableStateListOf<Entry>().apply { addAll(parseEntries(prefs.getString("entries","[]") ?: "[]")) } }
    var query by remember { mutableStateOf("") }; var results by remember { mutableStateOf(listOf<Entry>()) }
    var screen by rememberSaveable { mutableStateOf("timeline") }; var graphFullscreen by rememberSaveable { mutableStateOf(false) }; var graphOrientation by rememberSaveable { mutableStateOf("vertical") }; var showPreview by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf("Все") }; var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }; var detail by remember { mutableStateOf<Entry?>(null) }
    var showScales by remember { mutableStateOf(false) }; var scaleName by remember { mutableStateOf("") }
    var showDateOptions by remember { mutableStateOf(false) }
    var savedScales by remember { mutableStateOf(org.json.JSONArray(prefs.getString("scales","[]"))) }
    val scope= rememberCoroutineScope()
    fun goBack() { when { showDateOptions -> showDateOptions=false; showScales -> showScales=false; showPreview -> showPreview=false; detail!=null -> detail=null; graphFullscreen -> { graphFullscreen=false; (context as? ComponentActivity)?.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }; screen=="search" -> screen="timeline" } }
    BackHandler(enabled=showDateOptions||showScales||showPreview||detail!=null||graphFullscreen||screen=="search") { goBack() }
    fun save() { prefs.edit().putString("entries",serializeEntries(chosen)).apply() }
    fun search() { if(query.isBlank()) return; scope.launch { busy=true; message=""; try { results=withContext(Dispatchers.IO){ Wiki.search(query.trim()) }; if(results.isEmpty()) message="Ничего не найдено" } catch(e:Exception) { message="Ошибка загрузки. Проверьте интернет." } finally { busy=false } } }
    MaterialTheme(colorScheme=lightColorScheme(primary=Ink,secondary=Green,background=Color(0xFFF7F8FA),surface=Color.White)) {
        Scaffold(modifier=Modifier.pointerInput(screen,detail,graphFullscreen,showPreview,showScales,showDateOptions) {
            awaitEachGesture {
                val down=awaitFirstDown(requireUnconsumed=false)
                if(down.position.x<=28.dp.toPx()) {
                    var dx=0f; var dy=0f; var pressed=true
                    while(pressed) {
                        val event=awaitPointerEvent()
                        val change=event.changes.firstOrNull() ?: break
                        dx=change.position.x-down.position.x; dy=change.position.y-down.position.y
                        pressed=change.pressed
                        if(dx>90.dp.toPx() && kotlin.math.abs(dx)>kotlin.math.abs(dy)*1.35f) { goBack(); change.consume(); break }
                        if(!pressed) break
                    }
                }
            }
        },topBar={ if(!graphFullscreen) TopAppBar(title={ Text(if(screen=="search") "Поиск статей" else "График",fontWeight=FontWeight.Bold,color=Ink) },actions={Text(chosen.size.toString(),color=Muted,modifier=Modifier.padding(end=18.dp))},colors=TopAppBarDefaults.topAppBarColors(containerColor=Color(0xFFF7F8FA))) },
            bottomBar={ if(!graphFullscreen) NavigationBar(containerColor=Color.White) { NavigationBarItem(selected=screen=="timeline",onClick={screen="timeline"},icon={Icon(Icons.Default.Timeline,null)},label={Text("График")}); NavigationBarItem(selected=screen=="search",onClick={screen="search"},icon={Icon(Icons.Default.Search,null)},label={Text("Поиск статей")}) } }) { pad ->
            if(showScales) {
                AlertDialog(onDismissRequest={showScales=false},title={Text("Сохранённые шкалы")},text={
                    Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(value=scaleName,onValueChange={scaleName=it},label={Text("Название новой шкалы")},singleLine=true,modifier=Modifier.fillMaxWidth())
                        Button(onClick={
                            if(scaleName.isNotBlank() && chosen.isNotEmpty()) {
                                val all=org.json.JSONArray(prefs.getString("scales","[]"))
                                all.put(JSONObject().apply { put("name",scaleName.trim()); put("entries",serializeEntries(chosen)); put("savedAt",System.currentTimeMillis()) })
                                prefs.edit().putString("scales",all.toString()).apply(); savedScales=all; scaleName=""; showScales=false
                            }
                        },enabled=scaleName.isNotBlank()&&chosen.isNotEmpty(),modifier=Modifier.fillMaxWidth()){Text("Сохранить текущую шкалу")}
                        if(savedScales.length()==0) Text("Сохранённых шкал пока нет.",color=Muted)
                        else LazyColumn(Modifier.heightIn(max=300.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                            items((0 until savedScales.length()).map { savedScales.getJSONObject(it) }.reversed(),key={it.optString("savedAt")+it.optString("name")}) { scale ->
                                Row(verticalAlignment=Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f).clickable {
                                        chosen.clear(); chosen.addAll(parseEntries(scale.optString("entries","[]"))); save(); showScales=false; screen="timeline"
                                    }.padding(vertical=8.dp)) {
                                        Text(scale.optString("name"),fontWeight=FontWeight.SemiBold,color=Ink)
                                        Text("${parseEntries(scale.optString("entries","[]")).size} сущностей",fontSize=12.sp,color=Muted)
                                    }
                                    IconButton(onClick={
                                        val newer=org.json.JSONArray()
                                        for(i in 0 until savedScales.length()) { val x=savedScales.getJSONObject(i); if(x.optString("savedAt")!=scale.optString("savedAt") || x.optString("name")!=scale.optString("name")) newer.put(x) }
                                        prefs.edit().putString("scales",newer.toString()).apply(); savedScales=newer
                                    }) { Icon(Icons.Default.DeleteOutline,"Удалить шкалу",tint=Muted) }
                                }
                            }
                        }
                    }
                },confirmButton={TextButton(onClick={showScales=false}){Text("Закрыть")}})
            } else if(showDateOptions && detail!=null) {
                AlertDialog(onDismissRequest={showDateOptions=false},title={Text("Выбрать дату для графика")},text={
                    if(detail!!.dateOptions.isEmpty()) Text("Для этой сущности доступна только текущая дата: ${detail!!.dates}")
                    else LazyColumn(Modifier.heightIn(max=400.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        items(detail!!.dateOptions) { option ->
                            Row(Modifier.fillMaxWidth().clickable {
                                val old=detail!!
                                val index=old.dateOptions.indexOf(option)
                                val updated=old.copy(start=option.start,end=option.end,selectedDateOption=index)
                                val at=chosen.indexOfFirst { it.pageId==old.pageId }
                                if(at>=0) chosen[at]=updated
                                detail=updated; save(); showDateOptions=false
                            }.padding(10.dp),verticalAlignment=Alignment.CenterVertically) {
                                RadioButton(selected=option.start==detail!!.start && option.end==detail!!.end && detail!!.dateOptions.getOrNull(detail!!.selectedDateOption)==option,onClick=null)
                                Column(Modifier.padding(start=8.dp)) { Text(option.label,fontWeight=FontWeight.SemiBold,color=Ink); Text(if(option.start==null) "Дата не определена" else if(option.end!=null && option.end!=option.start) "${year(option.start)} — ${year(option.end)}" else year(option.start),fontSize=12.sp,color=Muted) }
                            }
                        }
                    }
                },confirmButton={TextButton(onClick={showDateOptions=false}){Text("Готово")}})
            } else if(showPreview && screen=="search") {
                AlertDialog(onDismissRequest={showPreview=false},title={Text("Добавлено на график · ${chosen.size}")},text={
                    if(chosen.isEmpty()) Text("Пока ничего не выбрано.")
                    else LazyColumn(Modifier.heightIn(max=420.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        items(chosen.toList(),key={"preview-"+it.pageId}) { e ->
                            Row(verticalAlignment=Alignment.CenterVertically) {
                                Box(Modifier.size(10.dp).clip(CircleShape).background(e.color))
                                Column(Modifier.weight(1f).padding(start=10.dp)) { Text(e.title,fontWeight=FontWeight.SemiBold,color=Ink); Text(e.dates,fontSize=12.sp,color=Muted) }
                                IconButton(onClick={chosen.remove(e);save()}) { Icon(Icons.Default.DeleteOutline,"Убрать",tint=Muted) }
                            }
                        }
                    }
                },confirmButton={TextButton(onClick={showPreview=false}){Text("Продолжить поиск")}},dismissButton={TextButton(onClick={screen="timeline";showPreview=false}){Text("Открыть график")}})
            } else if(detail!=null) {
                Column(Modifier.fillMaxSize().padding(pad).padding(18.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                    Text("← Назад",Modifier.clickable { detail=null },color=Blue)
                    Text(detail!!.title,fontSize=26.sp,fontWeight=FontWeight.Bold,color=Ink)
                    Text(detail!!.kind.uppercase(Locale.getDefault()),color=detail!!.color)
                    Text(detail!!.dates,fontSize=22.sp,fontWeight=FontWeight.SemiBold)
                    if(detail!!.dateOptions.size>1) OutlinedButton(onClick={showDateOptions=true},modifier=Modifier.fillMaxWidth()){Text("Выбрать дату · ${detail!!.dateOptions.size} вариантов")}
                    Text(detail!!.description,color=Muted)
                    Button(onClick={runCatching { context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(detail!!.url))) }},modifier=Modifier.fillMaxWidth()){Text("Открыть статью в Википедии")}
                    OutlinedButton(onClick={chosen.removeAll{it.pageId==detail!!.pageId};save();detail=null},modifier=Modifier.fillMaxWidth()){Text("Убрать со шкалы")}
                }
            } else if(screen=="search") {
                LazyColumn(Modifier.fillMaxSize().padding(pad),contentPadding=PaddingValues(16.dp,8.dp,16.dp,24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    item {
                        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                            Text("Поиск сущностей",fontSize=21.sp,fontWeight=FontWeight.Bold,color=Ink,modifier=Modifier.weight(1f))
                            OutlinedButton(onClick={showPreview=true}) { Text("На графике · ${chosen.size}") }
                        }
                    }
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
            } else if(graphFullscreen) {
                val visible=chosen.filter{filter=="Все"||it.kind==filter}.sortedBy{it.start?:Int.MAX_VALUE}
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically) {
                        Text("${visible.size} сущностей",Modifier.weight(1f),fontWeight=FontWeight.Bold,color=Ink)
                        TextButton(onClick={graphOrientation=if(graphOrientation=="vertical") "horizontal" else "vertical"; (context as? ComponentActivity)?.requestedOrientation=if(graphOrientation=="horizontal") ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT}) { Text(if(graphOrientation=="vertical") "Альбомная" else "Книжная") }
                        TextButton(onClick={graphFullscreen=false;(context as? ComponentActivity)?.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED}) { Text("Закрыть") }
                    }
                    if(visible.isEmpty()) Text("Добавьте статьи через поиск",Modifier.padding(20.dp),color=Muted)
                    else HistoricalGraph(visible,Modifier.fillMaxWidth().weight(1f),onSelect={detail=it},fullScreen=true)
                }
            } else LazyColumn(Modifier.fillMaxSize().padding(pad),contentPadding=PaddingValues(16.dp,8.dp,16.dp,24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                item { Row(verticalAlignment=Alignment.CenterVertically) { Text(chosen.size.toString()+" сущностей",Modifier.weight(1f),fontSize=13.sp,color=Muted); TextButton(onClick={showScales=true}){Text("Шкалы")}; TextButton(onClick={graphFullscreen=true}){Text("Развернуть")} } }
                item { Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(7.dp)) { listOf("Все","Человек","Событие","Эпоха","Сущность").forEach { f -> FilterChip(selected=filter==f,onClick={filter=f},label={Text(f)}) } } }
                val visible=chosen.filter{filter=="Все"||it.kind==filter}.sortedBy{it.start?:Int.MAX_VALUE}
                if(visible.isEmpty()) item { Column(Modifier.fillMaxWidth().padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally) { Icon(Icons.Default.Timeline,null,tint=Muted,modifier=Modifier.size(38.dp)); Text("Временная шкала пока пуста",color=Muted); Text("Откройте «Поиск статей» и добавьте нужные статьи",fontSize=12.sp,color=Muted) } }
                else {
                    item { HistoricalGraph(visible,Modifier.fillMaxWidth(),onSelect={detail=it},fullScreen=graphFullscreen) }
                    if(graphFullscreen) item { Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
                        TextButton(onClick={graphOrientation=if(graphOrientation=="vertical") "horizontal" else "vertical"; (context as? ComponentActivity)?.requestedOrientation=if(graphOrientation=="horizontal") ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT}) { Text(if(graphOrientation=="vertical") "Альбомная ориентация" else "Книжная ориентация") }
                        TextButton(onClick={graphFullscreen=false;(context as? ComponentActivity)?.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED}) { Text("Готово") }
                    } }
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
@Composable private fun HistoricalGraph(entries:List<Entry>,modifier:Modifier=Modifier,onSelect:(Entry)->Unit,fullScreen:Boolean) {
    val dates=entries.flatMap { listOfNotNull(it.start,it.end) }
    val low=(dates.minOrNull()?:0).toFloat()
    val high=(dates.maxOrNull()?:100).toFloat()
    val span=(high-low).coerceAtLeast(1f)
    val laneHeight=if(fullScreen) 76.dp else 52.dp
    val totalHeight=(entries.size*laneHeight.value+40).dp
    Canvas(modifier.height(totalHeight)) {
        val left=88.dp.toPx(); val right=12.dp.toPx(); val axisY=18.dp.toPx()
        val plotWidth=(size.width-left-right).coerceAtLeast(1f)
        val paint=android.graphics.Paint().apply { color=android.graphics.Color.DKGRAY; textSize=10.sp.toPx(); textAlign=android.graphics.Paint.Align.CENTER }
        for(i in 0..4) {
            val x=left+plotWidth*i/4
            drawLine(Muted.copy(alpha=.5f),androidx.compose.ui.geometry.Offset(x,axisY),androidx.compose.ui.geometry.Offset(x,size.height),strokeWidth=1.dp.toPx())
            val y=(low+span*i/4).toInt()
            drawContext.canvas.nativeCanvas.drawText(if(y<0) "${-y} до н.э." else y.toString(),x,11.dp.toPx(),paint)
        }
        entries.forEachIndexed { i,e ->
            val cy=axisY+12.dp.toPx()+laneHeight.toPx()*i+laneHeight.toPx()*.5f
            drawLine(Muted.copy(alpha=.2f),androidx.compose.ui.geometry.Offset(left,cy),androidx.compose.ui.geometry.Offset(size.width-right,cy),strokeWidth=1.dp.toPx())
            val x1=left+(((e.start?:low.toInt()).toFloat()-low)/span).coerceIn(0f,1f)*plotWidth
            val x2=left+(((e.end?:e.start?:low.toInt()).toFloat()-low)/span).coerceIn(0f,1f)*plotWidth
            if(e.start!=null && e.end!=null && e.end!=e.start) drawLine(e.color,androidx.compose.ui.geometry.Offset(minOf(x1,x2),cy),androidx.compose.ui.geometry.Offset(maxOf(x1,x2),cy),strokeWidth=8.dp.toPx())
            drawCircle(e.color,6.dp.toPx(),androidx.compose.ui.geometry.Offset(x1,cy))
            val labelPaint=android.graphics.Paint().apply { color=android.graphics.Color.DKGRAY; textSize=10.sp.toPx() }
            drawContext.canvas.nativeCanvas.drawText(e.title.take(13),4.dp.toPx(),cy+4.dp.toPx(),labelPaint)
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
        fun years(p:String):List<Int> {
            val a=claims.optJSONArray(p)?:return emptyList()
            return (0 until a.length()).mapNotNull { i ->
                val value=a.getJSONObject(i).optJSONObject("mainsnak")?.optJSONObject("datavalue")?.optJSONObject("value")?.optString("time") ?: return@mapNotNull null
                Regex("^([+-]?\\d{1,16})").find(value)?.groupValues?.get(1)?.toIntOrNull()
            }.distinct()
        }
        fun yr(p:String)=years(p).firstOrNull()
        val birth=yr("P569"); val death=yr("P570")
        val start=yr("P580")?:yr("P571")?:yr("P585")?:birth
        val end=yr("P582")?:yr("P576")?:death
        val options=mutableListOf<DateOption>()
        fun addOptions(property:String,label:String) { years(property).forEachIndexed { i,y -> options.add(DateOption(if(years(property).size>1) "$label · ${i+1}" else label,y,null)) } }
        val starts=years("P580"); val ends=years("P582")
        if(starts.isNotEmpty()) starts.forEachIndexed { i,y -> options.add(DateOption("Начало периода ${i+1}",y,ends.getOrNull(i) ?: ends.firstOrNull())) }
        val inceptions=years("P571"); val dissolved=years("P576")
        if(inceptions.isNotEmpty()) inceptions.forEachIndexed { i,y -> options.add(DateOption("Основание / существование ${i+1}",y,dissolved.getOrNull(i) ?: dissolved.firstOrNull())) }
        addOptions("P569","Рождение"); addOptions("P570","Смерть"); addOptions("P585","Дата события"); addOptions("P576","Прекращение")
        if(start!=null && options.none { it.start==start && it.end==end }) options.add(0,DateOption("Основной период",start,end))
        val articleText=runCatching {
            val article=json("https://ru.wikipedia.org/w/api.php?action=query&prop=extracts&explaintext=1&exchars=8000&titles="+q+"&format=json")
            val articlePages=article.getJSONObject("query").getJSONObject("pages")
            articlePages.getJSONObject(articlePages.keys().asSequence().first()).optString("extract")
        }.getOrDefault("")
        val rangeRegex=Regex("(?<!\\d)(\\d{3,4})\\s*[–—-]\\s*(\\d{3,4})(?!\\d)")
        val ranges=rangeRegex.findAll(articleText).take(12).toList()
        ranges.forEach { match ->
            val a=match.groupValues[1].toIntOrNull(); val b=match.groupValues[2].toIntOrNull()
            if(a!=null && b!=null && a in 100..2100 && b in 100..2100) {
                val contextStart=(match.range.first-38).coerceAtLeast(0)
                val contextEnd=(match.range.last+39).coerceAtMost(articleText.length)
                val label=articleText.substring(contextStart,contextEnd).replace(Regex("\\s+")," ").trim().take(64)
                options.add(DateOption("Из статьи: $label",a,b))
            }
        }
        val rangeCovered=ranges.flatMap { it.range.toList() }.toSet()
        Regex("(?<!\\d)(\\d{3,4})(?!\\d)").findAll(articleText).take(25).forEach { match ->
            val y=match.groupValues[1].toIntOrNull()
            if(y!=null && y in 100..2100 && options.none { it.start==y && it.end==null }) {
                val contextStart=(match.range.first-30).coerceAtLeast(0)
                val contextEnd=(match.range.last+31).coerceAtMost(articleText.length)
                val label=articleText.substring(contextStart,contextEnd).replace(Regex("\\s+")," ").trim().take(58)
                options.add(DateOption("Из статьи: $label",y,null))
            }
        }
        val uniqueOptions=options.distinctBy { Triple(it.label,it.start,it.end) }
        val defaultIndex=uniqueOptions.indexOfFirst { it.start==start && it.end==end }.coerceAtLeast(0)
        val kind=when { birth!=null||death!=null -> "Человек"; starts.isNotEmpty()||ends.isNotEmpty()||inceptions.isNotEmpty() -> "Событие"; else -> "Сущность" }
        val desc=entity.optJSONObject("descriptions")?.optJSONObject("ru")?.optString("value").orEmpty().ifBlank{e.description}
        return e.copy(description=desc,wd=wd,start=start,end=end,kind=kind,dateOptions=uniqueOptions,selectedDateOption=defaultIndex)
    }
}
