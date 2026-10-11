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
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
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
data class CategoryOption(val title:String,val size:Int=0)
data class Entry(val title:String, val description:String="", val pageId:Int=0, val wd:String="", val start:Int?=null, val end:Int?=null, val kind:String="Сущность", val url:String="https://ru.wikipedia.org", val dateOptions:List<DateOption> = emptyList(), val selectedDateOption:Int = 0, val hidden:Boolean=false, val categories:List<String> = emptyList()) {
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
        put("selectedDateOption",e.selectedDateOption); put("hidden",e.hidden)
        val categoryArray=org.json.JSONArray(); e.categories.forEach { categoryArray.put(it) }; put("categories",categoryArray)
        val opts=org.json.JSONArray(); e.dateOptions.forEach { d -> opts.put(JSONObject().apply { put("label",d.label); put("start",d.start?:JSONObject.NULL); put("end",d.end?:JSONObject.NULL) }) }; put("dateOptions",opts)
    }) }
    return a.toString()
}
private fun parseEntries(raw:String):List<Entry> = runCatching {
    val a=org.json.JSONArray(raw)
    (0 until a.length()).map { i ->
        val o=a.getJSONObject(i); val opts=o.optJSONArray("dateOptions")?:org.json.JSONArray()
        val dates=(0 until opts.length()).map { j -> val d=opts.getJSONObject(j); DateOption(d.optString("label"),if(d.isNull("start")) null else d.optInt("start"),if(d.isNull("end")) null else d.optInt("end")) }
        Entry(o.optString("title"),o.optString("description"),o.optInt("pageId"),o.optString("wd"),if(o.isNull("start")) null else o.optInt("start"),if(o.isNull("end")) null else o.optInt("end"),o.optString("kind","Сущность"),o.optString("url"),dates,o.optInt("selectedDateOption",0),o.optBoolean("hidden",false),(o.optJSONArray("categories")?:org.json.JSONArray()).let { a -> (0 until a.length()).map { a.optString(it) } })
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
    var categoryQuery by remember { mutableStateOf("") }; var categories by remember { mutableStateOf(listOf<CategoryOption>()) }
    var selectedCategory by remember { mutableStateOf<CategoryOption?>(null) }
    var searchMode by rememberSaveable { mutableStateOf("articles") }
    var browseParent by remember { mutableStateOf<String?>(null) }
    var browseCategories by remember { mutableStateOf(listOf<CategoryOption>()) }
    var categoryBusy by remember { mutableStateOf(false) }
    var categoryHasSearched by remember { mutableStateOf(false) }
    var recommendationHasSearched by remember { mutableStateOf(false) }
    var recommendationSeed by remember { mutableStateOf<Entry?>(null) }; var recommendationRadius by remember { mutableIntStateOf(5) }
    var recommendations by remember { mutableStateOf(listOf<Entry>()) }; var recommendationBusy by remember { mutableStateOf(false) }
    var screen by rememberSaveable { mutableStateOf("timeline") }; var graphFullscreen by rememberSaveable { mutableStateOf(false) }; var graphOrientation by rememberSaveable { mutableStateOf("vertical") }; var graphCompact by rememberSaveable { mutableStateOf(false) }; var showPreview by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf("Все") }; var busy by remember { mutableStateOf(false) }
    var pulsePageId by remember { mutableIntStateOf(-1) }; var pulseTrigger by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf("") }; var detail by remember { mutableStateOf<Entry?>(null) }
    var detailTextExpanded by remember(detail?.pageId) { mutableStateOf(false) }
    var categoriesExpanded by remember(detail?.pageId) { mutableStateOf(false) }
    var showScales by remember { mutableStateOf(false) }; var showHiddenEntries by remember { mutableStateOf(false) }; var scaleName by remember { mutableStateOf("") }
    var showDateOptions by remember { mutableStateOf(false) }
    var dateOptionQuery by remember { mutableStateOf("") }
    var dateOptionFilter by remember { mutableStateOf("Все") }
    var dateDraftOptionIndex by remember { mutableIntStateOf(0) }
    var savedScales by remember { mutableStateOf(org.json.JSONArray(prefs.getString("scales","[]"))) }
    val scope= rememberCoroutineScope()
    fun goBack() { when { showDateOptions -> showDateOptions=false; showScales -> showScales=false; showPreview -> showPreview=false; detail!=null -> detail=null; graphFullscreen -> { graphFullscreen=false; (context as? ComponentActivity)?.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }; screen=="search" -> screen="timeline" } }
    BackHandler(enabled=showDateOptions||showScales||showHiddenEntries||showPreview||detail!=null||graphFullscreen||screen=="search") { if(showHiddenEntries) showHiddenEntries=false else goBack() }
    fun save() { prefs.edit().putString("entries",serializeEntries(chosen)).apply() }
    fun search() { if(query.isBlank()) { message="Введите название статьи для поиска"; return }; scope.launch { busy=true; results=emptyList(); message=""; try { results=withContext(Dispatchers.IO){ Wiki.search(query.trim()) }; if(results.isEmpty()) message="По этому запросу статьи не найдены" } catch(e:Exception) { message="Ошибка загрузки статей. Проверьте интернет." } finally { busy=false } } }
    fun loadCategories() { if(categoryQuery.isBlank()) { message="Введите тему или начало названия категории"; return }; scope.launch { categoryBusy=true; categoryHasSearched=true; browseParent=null; browseCategories=emptyList(); message=""; try { categories=withContext(Dispatchers.IO){ Wiki.searchCategories(categoryQuery) }; browseCategories=categories; if(categories.isEmpty()) message="Категории не найдены — попробуйте более короткий запрос" } catch(e:Exception) { message="Не удалось загрузить категории" } finally { categoryBusy=false } } }
    fun loadCategoryTheme(theme:String) { categoryQuery=theme; scope.launch { categoryBusy=true; categoryHasSearched=true; browseParent=null; browseCategories=emptyList(); message=""; try { categories=withContext(Dispatchers.IO){ Wiki.searchCategories(theme) }; browseCategories=categories; if(categories.isEmpty()) message="По теме «$theme» категории не найдены" } catch(e:Exception) { message="Не удалось загрузить категории" } finally { categoryBusy=false } } }
    fun openCategory(category:CategoryOption) { scope.launch { categoryBusy=true; browseParent=category.title; browseCategories=emptyList(); message=""; try { browseCategories=withContext(Dispatchers.IO){ Wiki.subcategories(category.title) }; if(browseCategories.isEmpty()) message="Вложенных категорий нет. Нажмите «Показать статьи категории»." } catch(e:Exception) { message="Не удалось открыть подкатегории" } finally { categoryBusy=false } } }
    fun loadRecommendations(seedOverride:Entry?=null) { val seed=seedOverride ?: recommendationSeed; if(seed==null) { message="Сначала выберите статью для рекомендаций"; return }; recommendationSeed=seed; if(seed.start==null && seed.dateOptions.isEmpty()) { recommendationHasSearched=true; recommendations=emptyList(); message="У этой статьи не найдены даты, поэтому подобрать события по времени не получится."; return }; scope.launch { recommendationBusy=true; recommendationHasSearched=true; recommendations=emptyList(); message=""; try { recommendations=withContext(Dispatchers.IO){ Wiki.recommend(seed,recommendationRadius) }; if(recommendations.isEmpty()) message="Подходящие статьи не найдены. Попробуйте увеличить период или выбрать другую стартовую статью." } catch(e:Exception) { message="Не удалось загрузить рекомендации" } finally { recommendationBusy=false } } }
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
            if(showHiddenEntries) {
                val hiddenEntries=chosen.filter { it.hidden }.sortedBy { it.title.lowercase(Locale.ROOT) }
                AlertDialog(
                    onDismissRequest={showHiddenEntries=false},
                    title={Text("Скрытые статьи ("+hiddenEntries.size+")")},
                    text={
                        if(hiddenEntries.isEmpty()) Text("Скрытых статей нет.")
                        else LazyColumn(Modifier.heightIn(max=420.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            items(hiddenEntries,key={"hidden-"+it.pageId}) { e ->
                                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f).padding(end=8.dp)) {
                                        Text(e.title,fontWeight=FontWeight.SemiBold,color=Ink)
                                        Text(e.dates,fontSize=12.sp,color=Muted)
                                    }
                                    TextButton(onClick={
                                        val i=chosen.indexOfFirst { it.pageId==e.pageId }
                                        if(i>=0) chosen[i]=chosen[i].copy(hidden=false)
                                        save()
                                    }) { Text("Показать") }
                                }
                            }
                        }
                    },
                    confirmButton={TextButton(onClick={showHiddenEntries=false}){Text("Готово")}}
                )
            } else if(showScales) {
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
                val dateOwner=detail!!
                val dateChoices=dateOwner.dateOptions.mapIndexed { index, option -> option to index }
                    .filter { (option, _) ->
                        val matchesQuery=dateOptionQuery.isBlank() ||
                            option.label.contains(dateOptionQuery,ignoreCase=true) ||
                            (option.start?.let { year(it) }?.contains(dateOptionQuery,ignoreCase=true) == true) ||
                            (option.end?.let { year(it) }?.contains(dateOptionQuery,ignoreCase=true) == true)
                        val isRange=option.start!=null && option.end!=null && option.start!=option.end
                        val matchesFilter=when(dateOptionFilter) {
                            "Диапазоны" -> isRange
                            "Одиночные даты" -> !isRange
                            else -> true
                        }
                        matchesQuery && matchesFilter
                    }
                Column(Modifier.fillMaxSize().padding(pad).padding(horizontal=16.dp, vertical=8.dp)) {
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                        Text("← Назад",Modifier.clickable { showDateOptions=false }.padding(vertical=8.dp),color=Blue)
                        Spacer(Modifier.weight(1f))
                        Text("${dateOwner.dateOptions.size} вариантов",fontSize=12.sp,color=Muted)
                    }
                    Text("Дата или диапазон",fontSize=25.sp,fontWeight=FontWeight.Bold,color=Ink)
                    Text(dateOwner.title,fontSize=15.sp,color=Muted,maxLines=2)
                    Card(Modifier.fillMaxWidth().padding(top=12.dp,bottom=10.dp),colors=CardDefaults.cardColors(containerColor=Color(0xFFEAF0F8))) {
                        Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                            Text("Сейчас выбрано",fontSize=11.sp,color=Muted,fontWeight=FontWeight.SemiBold)
                            Text(dateOwner.dates,fontSize=21.sp,color=Ink,fontWeight=FontWeight.Bold)
                            Text("Выберите найденную в статье дату или период. После применения график и карточка обновятся.",fontSize=12.sp,color=Muted)
                        }
                    }
                    OutlinedTextField(value=dateOptionQuery,onValueChange={dateOptionQuery=it},modifier=Modifier.fillMaxWidth(),singleLine=true,label={Text("Найти дату или фрагмент статьи")},placeholder={Text("Например, 1812 или основание")})
                    Row(Modifier.fillMaxWidth().padding(top=8.dp,bottom=8.dp),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                        listOf("Все","Диапазоны","Одиночные даты").forEach { option ->
                            FilterChip(selected=dateOptionFilter==option,onClick={dateOptionFilter=option},label={Text(option,fontSize=11.sp)})
                        }
                    }
                    if(dateOwner.dateOptions.isEmpty()) {
                        Text("Для этой сущности дополнительные даты не найдены.",Modifier.padding(vertical=16.dp),color=Muted)
                    } else if(dateChoices.isEmpty()) {
                        Text("Ничего не найдено. Измените запрос или фильтр.",Modifier.padding(vertical=16.dp),color=Muted)
                    } else {
                        LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp),contentPadding=PaddingValues(bottom=12.dp)) {
                            items(dateChoices,key={it.second}) { choice ->
                                val option=choice.first
                                val index=choice.second
                                val selected=index==dateDraftOptionIndex
                                val isRange=option.start!=null && option.end!=null && option.start!=option.end
                                Card(Modifier.fillMaxWidth().clickable { dateDraftOptionIndex=index },colors=CardDefaults.cardColors(containerColor=if(selected) Color(0xFFEAF0F8) else Color.White),border=androidx.compose.foundation.BorderStroke(if(selected) 1.5.dp else 1.dp,if(selected) Blue else Color(0xFFE3E7ED)),shape=RoundedCornerShape(12.dp)) {
                                    Row(Modifier.fillMaxWidth().padding(12.dp),verticalAlignment=Alignment.Top) {
                                        RadioButton(selected=selected,onClick={dateDraftOptionIndex=index})
                                        Column(Modifier.weight(1f).padding(start=8.dp,top=5.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                                            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                                                Text(if(option.start==null) "Дата не определена" else if(isRange) "${year(option.start)} — ${year(option.end!!)}" else year(option.start),fontWeight=FontWeight.Bold,fontSize=16.sp,color=Ink)
                                                if(isRange) Surface(color=Green.copy(alpha=.12f),shape=RoundedCornerShape(6.dp)) { Text("ПЕРИОД",Modifier.padding(horizontal=6.dp,vertical=3.dp),fontSize=9.sp,color=Green,fontWeight=FontWeight.Bold) }
                                            }
                                            Text(option.label,fontSize=12.sp,color=Muted)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(top=8.dp,bottom=8.dp),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick={showDateOptions=false},modifier=Modifier.weight(1f)){Text("Отмена")}
                        Button(onClick={
                            val index=dateDraftOptionIndex.coerceIn(0,dateOwner.dateOptions.lastIndex.coerceAtLeast(0))
                            val option=dateOwner.dateOptions.getOrNull(index)
                            if(option!=null) {
                                val updated=dateOwner.copy(start=option.start,end=option.end,selectedDateOption=index)
                                val at=chosen.indexOfFirst { it.pageId==dateOwner.pageId }
                                if(at>=0) chosen[at]=updated
                                detail=updated
                                save()
                            }
                            showDateOptions=false
                        },enabled=dateOwner.dateOptions.isNotEmpty(),modifier=Modifier.weight(1f)){Text("Применить")}
                    }
                }
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
                Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(18.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                    Text("← Назад",Modifier.clickable { detail=null },color=Blue)
                    Text(detail!!.title,fontSize=26.sp,fontWeight=FontWeight.Bold,color=Ink)
                    Text(detail!!.kind.uppercase(Locale.getDefault()),color=detail!!.color)
                    Text(detail!!.dates,fontSize=22.sp,fontWeight=FontWeight.SemiBold)
                    if(detail!!.dateOptions.size>1) OutlinedButton(onClick={dateDraftOptionIndex=detail!!.selectedDateOption.coerceIn(0,detail!!.dateOptions.lastIndex);dateOptionQuery="";dateOptionFilter="Все";showDateOptions=true},modifier=Modifier.fillMaxWidth()){Text("Выбрать дату или диапазон · ${detail!!.dateOptions.size}")}
                    if(detail!!.description.isNotBlank()) {
                        val articleText=detail!!.description.trim()
                        val previewLimit=600
                        Text(
                            if(detailTextExpanded || articleText.length<=previewLimit) articleText else articleText.take(previewLimit).trimEnd()+"…",
                            color=Muted
                        )
                        if(articleText.length>previewLimit) {
                            TextButton(onClick={detailTextExpanded=!detailTextExpanded},modifier=Modifier.align(Alignment.Start)) {
                                Text(if(detailTextExpanded) "Свернуть" else "Читать далее")
                            }
                        }
                    }
                    Card(
                        modifier=Modifier.fillMaxWidth(),
                        colors=CardDefaults.cardColors(containerColor=Color(0xFFF1F4F8)),
                        shape=RoundedCornerShape(12.dp)
                    ) {
                        Column(Modifier.fillMaxWidth()) {
                            Row(
                                Modifier.fillMaxWidth().clickable { categoriesExpanded=!categoriesExpanded }.padding(horizontal=14.dp,vertical=12.dp),
                                verticalAlignment=Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text("Категории",fontWeight=FontWeight.SemiBold,color=Ink)
                                    Text(
                                        if(detail!!.categories.isEmpty()) "Не найдены" else "${detail!!.categories.size} категорий",
                                        fontSize=12.sp,color=Muted
                                    )
                                }
                                Text(if(categoriesExpanded) "▲" else "▼",color=Blue)
                            }
                            if(categoriesExpanded) {
                                Divider(color=Color(0xFFDCE2E9))
                                if(detail!!.categories.isEmpty()) {
                                    Text("Для этой статьи категории не найдены.",Modifier.padding(14.dp),fontSize=13.sp,color=Muted)
                                } else {
                                    Column(
                                        Modifier.fillMaxWidth().padding(horizontal=14.dp,vertical=10.dp),
                                        verticalArrangement=Arrangement.spacedBy(8.dp)
                                    ) {
                                        detail!!.categories.forEach { category ->
                                            Text(category,color=Ink,fontSize=13.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Button(onClick={val current=detail!!; recommendationSeed=current; recommendationRadius=5; recommendations=emptyList(); recommendationHasSearched=false; message=""; screen="search"; searchMode="recommendations"; detail=null; loadRecommendations(current)},modifier=Modifier.fillMaxWidth()){Text("Получить рекомендации")}
                    Button(onClick={runCatching { context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(detail!!.url))) }},modifier=Modifier.fillMaxWidth()){Text("Открыть статью в Википедии")}
                    if(chosen.any { it.pageId==detail!!.pageId }) {
                        OutlinedButton(onClick={chosen.removeAll{it.pageId==detail!!.pageId};if(recommendationSeed?.pageId==detail!!.pageId) recommendationSeed=null;save();detail=null},modifier=Modifier.fillMaxWidth()){Text("Убрать со шкалы")}
                    } else {
                        Button(onClick={
                            val current=detail!!
                            if(chosen.none { it.pageId==current.pageId }) { chosen.add(current); save(); recommendationSeed=current; recommendations=emptyList(); recommendationHasSearched=false }
                            detail=null
                        },modifier=Modifier.fillMaxWidth()){Text("Добавить на график")}
                    }
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
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(7.dp)) {
                            FilterChip(selected=searchMode=="articles",onClick={searchMode="articles"},label={Text("Поиск статей")})
                            FilterChip(selected=searchMode=="categories",onClick={searchMode="categories"},label={Text("Каталог категорий")})
                            FilterChip(selected=searchMode=="recommendations",onClick={searchMode="recommendations"},label={Text("Рекомендации")})
                        }
                    }
                    if(searchMode=="articles") {
                        item {
                            Text("Поиск по названию статьи. Он работает независимо от категорий и рекомендаций.",fontSize=12.sp,color=Muted)
                            OutlinedTextField(value=query,onValueChange={query=it},modifier=Modifier.fillMaxWidth(),placeholder={Text("Например, Ледовое побоище")},leadingIcon={Icon(Icons.Default.Search,null)},trailingIcon={if(busy) CircularProgressIndicator(Modifier.size(22.dp),strokeWidth=2.dp) else IconButton(onClick={search()}){Icon(Icons.Default.Search,"Искать")}},singleLine=true,keyboardOptions=KeyboardOptions(imeAction=ImeAction.Search),keyboardActions=KeyboardActions(onSearch={search()}),shape=RoundedCornerShape(16.dp))
                            if(message.isNotBlank()) Text(message,color=Muted,fontSize=12.sp)
                        }
                    }
                    if(searchMode=="categories") {
                        item {
                            Text("Каталог категорий — как рубрикатор: выберите тему или найдите категорию по названию.",fontSize=12.sp,color=Muted)
                            OutlinedTextField(value=categoryQuery,onValueChange={categoryQuery=it},modifier=Modifier.fillMaxWidth(),placeholder={Text("Название категории")},singleLine=true,keyboardOptions=KeyboardOptions(imeAction=ImeAction.Search),keyboardActions=KeyboardActions(onSearch={loadCategories()}) )
                            Button(onClick={loadCategories()},modifier=Modifier.fillMaxWidth(),enabled=!categoryBusy) { Text(if(categoryBusy) "Загружаем…" else "Найти категории") }
                            if(categoryHasSearched) TextButton(onClick={categoryHasSearched=false; browseParent=null; browseCategories=emptyList(); categories=emptyList(); categoryQuery=""; selectedCategory=null; message=""}) { Text("Вернуться ко всем темам") }
                        }
                        if(browseParent==null && !categoryHasSearched) {
                            item {
                                Text("Популярные разделы",fontSize=17.sp,fontWeight=FontWeight.Bold,color=Ink)
                                Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
                                    listOf("История","География","Биографии","Наука","Техника","Культура","Искусство","Литература","Общество","Спорт","Природа","Религия").chunked(2).forEach { pair ->
                                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                            pair.forEach { theme -> OutlinedButton(onClick={loadCategoryTheme(theme)},modifier=Modifier.weight(1f)) { Text(theme) } }
                                        }
                                    }
                                }
                            }
                        }
                        if(browseParent!=null) item {
                            Row(verticalAlignment=Alignment.CenterVertically) {
                                Text("Подкатегории: "+browseParent,Modifier.weight(1f),fontWeight=FontWeight.Bold,color=Ink)
                                TextButton(onClick={browseParent=null;browseCategories=categories}) { Text("Назад") }
                            }
                        }
                        if(browseCategories.isNotEmpty()) {
                            item { Label(if(browseParent==null) "КАТЕГОРИИ" else "ВЛОЖЕННЫЕ КАТЕГОРИИ",browseCategories.size.toString()) }
                            items(browseCategories,key={"browse-cat-"+it.title}) { cat ->
                                Card(colors=CardDefaults.cardColors(containerColor=Color.White),shape=RoundedCornerShape(12.dp)) {
                                    Column(Modifier.fillMaxWidth().padding(10.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                                        Text(cat.title,fontWeight=FontWeight.SemiBold,color=Ink)
                                        Text(if(cat.size>0) cat.size.toString()+" страниц в категории" else "Подкатегория",fontSize=11.sp,color=Muted)
                                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                            OutlinedButton(onClick={openCategory(cat)},modifier=Modifier.weight(1f)) { Text("Подкатегории") }
                                            Button(
                                                onClick = {
                                                    scope.launch {
                                                        busy = true
                                                        results = emptyList()
                                                        searchMode = "articles"
                                                        selectedCategory = null
                                                        message = "Загружаем статьи категории…"
                                                        try {
                                                            results = withContext(Dispatchers.IO) {
                                                                Wiki.searchInCategory(cat.title)
                                                            }
                                                            message = if (results.isEmpty()) {
                                                                "В категории нет доступных статей"
                                                            } else {
                                                                "Категория: " + cat.title
                                                            }
                                                        } catch (e: Exception) {
                                                            message = "Не удалось загрузить статьи категории"
                                                        } finally {
                                                            busy = false
                                                        }
                                                    }
                                                },
                                                modifier = Modifier.weight(1f)
                                            ) { Text("Статьи") }
                                        }
                                    }
                                }
                            }
                        }
                        if(selectedCategory!=null) item {
                            Card(colors=CardDefaults.cardColors(containerColor=Color.White),shape=RoundedCornerShape(12.dp)) {
                                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                    Text("Выбрана категория: "+selectedCategory!!.title,fontWeight=FontWeight.SemiBold,color=Ink)
                                    Button(onClick={scope.launch { busy=true; results=emptyList(); try { results=withContext(Dispatchers.IO){Wiki.searchInCategory(selectedCategory!!.title)}; if(results.isEmpty()) message="В категории нет доступных статей" } catch(e:Exception) { message="Не удалось загрузить статьи категории" } finally { busy=false } };searchMode="articles"},modifier=Modifier.fillMaxWidth()) { Text("Показать статьи этой категории") }
                                    TextButton(onClick={selectedCategory=null}) { Text("Сбросить категорию") }
                                }
                            }
                        }
                        if(message.isNotBlank()) item { Text(message,color=Muted,fontSize=12.sp) }
                    }
                    if(searchMode=="articles" && results.isNotEmpty()) {
                        item { Label("ПРЯМОЙ ПОИСК",results.size.toString()) }
                        items(results,key={ "r"+it.pageId }) { e ->
                            Card(Modifier.fillMaxWidth().clickable {
                                scope.launch {
                                    busy=true
                                    try { detail=withContext(Dispatchers.IO){Wiki.enrich(e)} }
                                    catch(ex:Exception) { detail=e; message="Не все сведения удалось загрузить" }
                                    finally { busy=false }
                                }
                            },colors=CardDefaults.cardColors(containerColor=Color.White),shape=RoundedCornerShape(14.dp)) {
                                Row(Modifier.fillMaxWidth().padding(12.dp),verticalAlignment=Alignment.CenterVertically) {
                                    Box(Modifier.size(10.dp).clip(CircleShape).background(e.color))
                                    Column(Modifier.weight(1f).padding(horizontal=12.dp)) {
                                        Text(e.title,fontWeight=FontWeight.SemiBold,color=Ink)
                                        if(e.description.isNotBlank()) Text(e.description,color=Muted,fontSize=12.sp,maxLines=2)
                                    }
                                    IconButton(onClick={
                                        if(chosen.any{it.pageId==e.pageId}) message="Уже добавлено"
                                        else scope.launch {
                                            busy=true; message="Загружаем даты из статьи…"
                                            try { val full=withContext(Dispatchers.IO){Wiki.enrich(e)}; chosen.add(full); save(); recommendationSeed=full; recommendations=emptyList(); recommendationHasSearched=false; message="Добавлено на график. Рекомендации доступны в отдельном режиме." }
                                            catch(ex:Exception) { chosen.add(e); save(); recommendationSeed=e; recommendations=emptyList(); recommendationHasSearched=false; message="Статья добавлена, но даты загрузить не удалось" }
                                            finally { busy=false }
                                        }
                                    }) { Icon(Icons.Default.Add,"Добавить",tint=Blue) }
                                }
                            }
                        }
                    }
                    if(searchMode=="articles" && recommendationHasSearched && recommendations.isNotEmpty()) {
                        item { Spacer(Modifier.height(4.dp)); Label("РЕКОМЕНДАЦИИ · ±"+recommendationRadius+" ЛЕТ",recommendations.size.toString()) }
                        items(recommendations,key={ "article-rec-"+it.pageId }) { e ->
                            Card(Modifier.fillMaxWidth().clickable {
                                scope.launch {
                                    busy=true
                                    try { detail=withContext(Dispatchers.IO){Wiki.enrich(e)} }
                                    catch(ex:Exception) { detail=e; message="Не все сведения удалось загрузить" }
                                    finally { busy=false }
                                }
                            },colors=CardDefaults.cardColors(containerColor=Color(0xFFFFF7E8)),shape=RoundedCornerShape(14.dp)) {
                                Row(Modifier.fillMaxWidth().padding(12.dp),verticalAlignment=Alignment.CenterVertically) {
                                    Box(Modifier.size(10.dp).clip(CircleShape).background(Amber))
                                    Column(Modifier.weight(1f).padding(horizontal=12.dp)) {
                                        Text(e.title,fontWeight=FontWeight.SemiBold,color=Ink)
                                        if(e.description.isNotBlank()) Text(e.description,color=Muted,fontSize=12.sp,maxLines=2)
                                        Text("Найдено по рекомендациям",fontSize=10.sp,color=Amber)
                                    }
                                    IconButton(onClick={
                                        if(chosen.any{it.pageId==e.pageId}) message="Уже добавлено"
                                        else scope.launch {
                                            busy=true
                                            try { val full=withContext(Dispatchers.IO){Wiki.enrich(e)}; chosen.add(full); save(); recommendationSeed=full; message="Добавлено на график"; recommendations= recommendations.filterNot{it.pageId==e.pageId} }
                                            catch(ex:Exception) { message="Не удалось добавить статью" }
                                            finally { busy=false }
                                        }
                                    }) { Icon(Icons.Default.Add,"Добавить на график",tint=Amber) }
                                }
                            }
                        }
                    }
                    if(searchMode=="recommendations") {
                        item {
                            Card(colors=CardDefaults.cardColors(containerColor=Color.White),shape=RoundedCornerShape(14.dp)) {
                                Column(Modifier.fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                                    Text("События рядом с исходной статьёй",fontWeight=FontWeight.Bold,color=Ink)
                                    Text(if(recommendationSeed==null) "Сначала найдите и добавьте стартовую статью в режиме «Поиск статей»." else recommendationSeed!!.title+" · "+(recommendationSeed!!.start?.let{year(it)} ?: "год не определён"),fontSize=12.sp,color=Muted)
                                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                                        listOf(1,5,10).forEach { radius -> FilterChip(selected=recommendationRadius==radius,onClick={recommendationRadius=radius},label={Text("±$radius лет")}) }
                                    }
                                    Button(onClick={loadRecommendations()},modifier=Modifier.fillMaxWidth(),enabled=!recommendationBusy&&recommendationSeed!=null) {
                                        if(recommendationBusy) CircularProgressIndicator(Modifier.size(18.dp),strokeWidth=2.dp) else Text("Найти рекомендации")
                                    }
                                }
                            }
                        }
                    }
                    if(searchMode=="recommendations" && recommendationHasSearched && recommendations.isNotEmpty()) {
                        item { Label("СТАТЬИ РЯДОМ ПО ВРЕМЕНИ",recommendations.size.toString()) }
                        items(recommendations,key={ "rec-"+it.pageId }) { e ->
                            Card(Modifier.fillMaxWidth().clickable {
                                scope.launch {
                                    busy=true
                                    try { detail=withContext(Dispatchers.IO){Wiki.enrich(e)} }
                                    catch(ex:Exception) { detail=e; message="Не все сведения удалось загрузить" }
                                    finally { busy=false }
                                }
                            },colors=CardDefaults.cardColors(containerColor=Color(0xFFFFF7E8)),shape=RoundedCornerShape(12.dp)) {
                                Row(Modifier.fillMaxWidth().padding(10.dp),verticalAlignment=Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) { Text(e.title,fontWeight=FontWeight.SemiBold,color=Ink); if(e.description.isNotBlank()) Text(e.description,fontSize=12.sp,color=Muted,maxLines=2); Text("Нажмите для подробностей",fontSize=10.sp,color=Amber) }
                                    IconButton(onClick={
                                        if(chosen.any{it.pageId==e.pageId}) message="Уже добавлено"
                                        else scope.launch { busy=true; try { val full=withContext(Dispatchers.IO){Wiki.enrich(e)}; chosen.add(full); save(); message="Добавлено на шкалу"; recommendations=recommendations.filterNot{it.pageId==e.pageId} } catch(ex:Exception) { message="Не удалось добавить статью" } finally { busy=false } }
                                    }) { Icon(Icons.Default.Add,"Добавить на график",tint=Blue) }
                                }
                            }
                        }
                    }
                    if(searchMode=="recommendations" && message.isNotBlank()) {
                        item { Text(message,color=Muted,fontSize=12.sp) }
                    }
                    item { Text("Выбранные статьи · ${chosen.size}",fontSize=18.sp,fontWeight=FontWeight.Bold,color=Ink) }
                    if(chosen.isEmpty()) item { Text("Добавляй статьи через поиск — даты появятся на общей шкале.",color=Muted,fontSize=13.sp) }
                    items(chosen.toList(),key={ "selected-"+it.pageId }) { e ->
                        Card(colors=CardDefaults.cardColors(containerColor=Color.White),shape=RoundedCornerShape(12.dp)) {
                            Row(Modifier.fillMaxWidth().padding(8.dp),verticalAlignment=Alignment.CenterVertically) {
                                IconButton(onClick={if(!e.hidden){pulsePageId=e.pageId;pulseTrigger++}else message="Сначала покажите статью на графике"},modifier=Modifier.size(40.dp)) {
                                    Box(Modifier.size(20.dp).clip(CircleShape).background(e.color).padding(3.dp),contentAlignment=Alignment.Center) {
                                        Box(Modifier.fillMaxSize().clip(CircleShape).background(Color.White.copy(alpha=0.8f)))
                                    }
                                }
                                Column(Modifier.weight(1f).padding(horizontal=4.dp)) { Text(e.title,fontWeight=FontWeight.SemiBold,color=if(e.hidden) Muted else Ink); Text(e.dates+" · "+if(e.hidden) "скрыта" else "на графике",fontSize=12.sp,color=Muted) }
                                TextButton(onClick={val i=chosen.indexOfFirst{it.pageId==e.pageId};if(i>=0)chosen[i]=e.copy(hidden=!e.hidden);save()}) { Text(if(e.hidden) "Показать" else "Скрыть") }
                                IconButton(onClick={chosen.remove(e);if(recommendationSeed?.pageId==e.pageId) recommendationSeed=null;save()}) { Icon(Icons.Default.DeleteOutline,"Удалить из набора",tint=Muted) }
                            }
                        }
                    }
                }
            } else if(graphFullscreen) {
                val visible=chosen.filter{!it.hidden}.sortedBy{it.start?:Int.MAX_VALUE}
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically) {
                        Text("${visible.size} сущностей",Modifier.weight(1f),fontWeight=FontWeight.Bold,color=Ink)
                        TextButton(onClick={graphCompact=!graphCompact}) { Text(if(graphCompact) "Обычный вид" else "Компактно") }
                        TextButton(onClick={graphOrientation=if(graphOrientation=="vertical") "horizontal" else "vertical"; (context as? ComponentActivity)?.requestedOrientation=if(graphOrientation=="horizontal") ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT}) { Text(if(graphOrientation=="vertical") "Альбомная" else "Книжная") }
                        TextButton(onClick={graphFullscreen=false;(context as? ComponentActivity)?.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED}) { Text("Закрыть") }
                    }
                    if(visible.isEmpty()) Text("Добавьте статьи через поиск",Modifier.padding(20.dp),color=Muted)
                    else Box(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
                        HistoricalGraph(visible,Modifier.fillMaxWidth(),onSelect={detail=it},fullScreen=true,compact=graphCompact,pulsePageId=pulsePageId,pulseTrigger=pulseTrigger)
                    }
                }
            } else LazyColumn(Modifier.fillMaxSize().padding(pad),contentPadding=PaddingValues(16.dp,8.dp,16.dp,24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                item { Row(verticalAlignment=Alignment.CenterVertically) {
                    Text(chosen.size.toString()+" сущностей",Modifier.weight(1f),fontSize=13.sp,color=Muted)
                    if(chosen.any { it.hidden }) TextButton(onClick={showHiddenEntries=true}){Text("Скрытые · "+chosen.count { it.hidden })}
                    TextButton(onClick={showScales=true}){Text("Шкалы")}
                    TextButton(onClick={graphCompact=!graphCompact}){Text(if(graphCompact) "Обычный вид" else "Компактно")}
                    TextButton(onClick={graphFullscreen=true}){Text("Развернуть")}
                } }
                val visible=chosen.filter{!it.hidden}.sortedBy{it.start?:Int.MAX_VALUE}
                if(visible.isEmpty()) item { Column(Modifier.fillMaxWidth().padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally) { Icon(Icons.Default.Timeline,null,tint=Muted,modifier=Modifier.size(38.dp)); Text("Временная шкала пока пуста",color=Muted); Text("Откройте «Поиск статей» и добавьте нужные статьи",fontSize=12.sp,color=Muted) } }
                else {
                    item { HistoricalGraph(visible,Modifier.fillMaxWidth(),onSelect={detail=it},fullScreen=false,compact=graphCompact,pulsePageId=pulsePageId,pulseTrigger=pulseTrigger) }
                    if(graphFullscreen) item { Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
                        TextButton(onClick={graphOrientation=if(graphOrientation=="vertical") "horizontal" else "vertical"; (context as? ComponentActivity)?.requestedOrientation=if(graphOrientation=="horizontal") ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT}) { Text(if(graphOrientation=="vertical") "Альбомная ориентация" else "Книжная ориентация") }
                        TextButton(onClick={graphFullscreen=false;(context as? ComponentActivity)?.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED}) { Text("Готово") }
                    } }
                    items(visible,key={"s"+it.pageId}) { e ->
                        Card(Modifier.fillMaxWidth().clickable{detail=e},colors=CardDefaults.cardColors(containerColor=Color.White),shape=RoundedCornerShape(15.dp)) {
                            Row(Modifier.padding(if(graphCompact) 8.dp else 12.dp),verticalAlignment=Alignment.CenterVertically) {
                                Column(Modifier.width(if(graphCompact) 62.dp else 75.dp),horizontalAlignment=Alignment.CenterHorizontally) { Text(e.start?.let{year(it)}?:"—",fontSize=if(graphCompact) 10.sp else 11.sp,color=Ink,fontWeight=FontWeight.SemiBold); Box(Modifier.padding(top=if(graphCompact) 3.dp else 6.dp).width(3.dp).height(if(graphCompact) 14.dp else 22.dp).background(e.color)) ; if(e.end!=null&&e.end!=e.start) Text(year(e.end),fontSize=if(graphCompact) 9.sp else 10.sp,color=Muted) }
                                Column(Modifier.weight(1f).padding(horizontal=if(graphCompact) 5.dp else 8.dp)) { Text(e.title,fontWeight=FontWeight.SemiBold,color=Ink,fontSize=if(graphCompact) 13.sp else 14.sp,maxLines=if(graphCompact) 1 else 2); Text(e.kind+" · "+e.dates,fontSize=if(graphCompact) 10.sp else 11.sp,color=Muted,maxLines=1); if(e.description.isNotBlank()) Text(e.description,fontSize=if(graphCompact) 11.sp else 12.sp,color=Muted,maxLines=if(graphCompact) 1 else 2) }
                                TextButton(
                                    onClick={
                                        val i=chosen.indexOfFirst { it.pageId==e.pageId }
                                        if(i>=0) chosen[i]=chosen[i].copy(hidden=true)
                                        save()
                                    },
                                    contentPadding=PaddingValues(horizontal=6.dp,vertical=0.dp)
                                ) { Text("Скрыть",fontSize=if(graphCompact) 10.sp else 12.sp) }
                                IconButton(onClick={chosen.remove(e);save()},modifier=Modifier.size(if(graphCompact) 28.dp else 32.dp)){Icon(Icons.Default.DeleteOutline,"Убрать",tint=Muted)}
                            }
                        }
                    }
                }
            }
        }
    }
}
@Composable private fun HistoricalGraph(entries:List<Entry>,modifier:Modifier=Modifier,onSelect:(Entry)->Unit,fullScreen:Boolean,compact:Boolean=false,pulsePageId:Int?=null,pulseTrigger:Int=0) {
    var pulseOn by remember { mutableStateOf(false) }
    LaunchedEffect(pulseTrigger,pulsePageId) {
        if(pulsePageId!=null) {
            repeat(6) { pulseOn=(it%2==0); delay(230) }
            pulseOn=false
        }
    }
    val dates=entries.flatMap { listOfNotNull(it.start,it.end) }
    val low=(dates.minOrNull()?:0).toFloat()
    val high=(dates.maxOrNull()?:100).toFloat()
    val span=(high-low).coerceAtLeast(1f)
    val baseLaneHeight=when { compact && fullScreen -> 42.dp; compact -> 30.dp; fullScreen -> 66.dp; else -> 52.dp }
    var zoom by remember(entries) { mutableStateOf(1f) }
    var zoomY by remember(entries) { mutableStateOf(1f) }
    val laneHeight=baseLaneHeight*zoomY
    val totalHeight=(entries.size*laneHeight.value+(if(compact) 30 else 44)).dp
    var panFraction by remember(entries) { mutableStateOf(0f) }
    var selectedYear by remember(entries) { mutableStateOf<Int?>(null) }
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(horizontal=6.dp, vertical=2.dp), verticalAlignment=Alignment.CenterVertically, horizontalArrangement=Arrangement.spacedBy(2.dp)) {
            Text("Г",fontSize=11.sp,color=Muted,fontWeight=FontWeight.SemiBold)
            TextButton(onClick={ zoom=(zoom/1.5f).coerceAtLeast(1f); if(zoom<=1f) panFraction=0f }, contentPadding=PaddingValues(horizontal=6.dp, vertical=0.dp), modifier=Modifier.height(30.dp)) { Text("−") }
            Text("${String.format(Locale.getDefault(),"%.1f",zoom)}×",fontSize=11.sp,color=Ink,maxLines=1)
            TextButton(onClick={ zoom=(zoom*1.5f).coerceAtMost(32f) }, contentPadding=PaddingValues(horizontal=6.dp, vertical=0.dp), modifier=Modifier.height(30.dp)) { Text("+") }
            Spacer(Modifier.width(4.dp))
            Text("В",fontSize=11.sp,color=Muted,fontWeight=FontWeight.SemiBold)
            TextButton(onClick={ zoomY=(zoomY/1.25f).coerceAtLeast(0.65f) }, contentPadding=PaddingValues(horizontal=6.dp, vertical=0.dp), modifier=Modifier.height(30.dp)) { Text("−") }
            Text("${String.format(Locale.getDefault(),"%.1f",zoomY)}×",fontSize=11.sp,color=Ink,maxLines=1)
            TextButton(onClick={ zoomY=(zoomY*1.25f).coerceAtMost(4f) }, contentPadding=PaddingValues(horizontal=6.dp, vertical=0.dp), modifier=Modifier.height(30.dp)) { Text("+") }
            Spacer(Modifier.weight(1f))
            TextButton(onClick={ zoom=1f; zoomY=1f; panFraction=0f; selectedYear=null }, contentPadding=PaddingValues(horizontal=5.dp, vertical=0.dp), modifier=Modifier.height(30.dp)) { Text("↺",fontSize=16.sp) }
        }
        Canvas(
            Modifier.fillMaxWidth().height(totalHeight).clip(RoundedCornerShape(14.dp))
                .background(Color.White)
                .pointerInput(entries) {
                    awaitEachGesture {
                        val down=awaitFirstDown(requireUnconsumed=false)
                        var previousCentroidX:Float? = null
                        var previousSpan:Float? = null
                        do {
                            val event=awaitPointerEvent()
                            val pressed=event.changes.filter { it.pressed }
                            if(pressed.size>=2) {
                                val cx=pressed.map { it.position.x }.average().toFloat()
                                val cy=pressed.map { it.position.y }.average().toFloat()
                                val spanNow=(pressed.map { change ->
                                    val dx=change.position.x-cx
                                    val dy=change.position.y-cy
                                    kotlin.math.sqrt(dx*dx+dy*dy)
                                }.average().toFloat()*2f).coerceAtLeast(1f)
                                val oldSpan=previousSpan
                                if(oldSpan!=null && previousCentroidX!=null) {
                                    val left=88.dp.toPx()
                                    val plot=(size.width-left-12.dp.toPx()).coerceAtLeast(1f)
                                    val oldZoom=zoom
                                    val newZoom=(oldZoom*(spanNow/oldSpan)).coerceIn(1f,64f)
                                    val oldPan=plot*(oldZoom-1f)*panFraction
                                    val anchor=((previousCentroidX!!-left+oldPan)/oldZoom).coerceIn(0f,plot)
                                    val newPan=(anchor*newZoom-(cx-left)).coerceIn(0f,plot*(newZoom-1f))
                                    zoom=newZoom
                                    zoomY=(zoomY*(spanNow/oldSpan)).coerceIn(0.65f,4f)
                                    panFraction=if(newZoom<=1f) 0f else (newPan/(plot*(newZoom-1f))).coerceIn(0f,1f)
                                }
                                previousSpan=spanNow
                                previousCentroidX=cx
                                pressed.forEach { it.consume() }
                            } else if(pressed.size==1) {
                                previousSpan=null
                                previousCentroidX=null
                                val dx=pressed[0].position.x-down.position.x
                                val dy=pressed[0].position.y-down.position.y
                                if(kotlin.math.abs(dx)>=kotlin.math.abs(dy) || kotlin.math.abs(dy)<viewConfiguration.touchSlop) {
                                    val left=88.dp.toPx()
                                    val plot=(size.width-left-12.dp.toPx()).coerceAtLeast(1f)
                                    val maxPan=plot*(zoom-1f)
                                    val pan=maxPan*panFraction
                                    val ratio=((pressed[0].position.x-left+pan)/(plot*zoom)).coerceIn(0f,1f)
                                    selectedYear=(low+span*ratio).toInt()
                                }
                            } else {
                                previousSpan=null
                                previousCentroidX=null
                            }
                        } while(event.changes.any { it.pressed })
                    }
                }
        ) {
            val left=88.dp.toPx()
            val right=12.dp.toPx()
            val axisY=22.dp.toPx()
            val plotWidth=(size.width-left-right).coerceAtLeast(1f)
            drawRect(Color.White,topLeft=androidx.compose.ui.geometry.Offset.Zero,size=androidx.compose.ui.geometry.Size(left,size.height))
            drawRect(Color.White,topLeft=androidx.compose.ui.geometry.Offset(left,0f),size=androidx.compose.ui.geometry.Size(size.width-left,axisY))
            val contentWidth=plotWidth*zoom
            val maxPan=(contentWidth-plotWidth).coerceAtLeast(0f)
            val pan=maxPan*panFraction
            val visibleStart=low+span*(pan/contentWidth.coerceAtLeast(1f))
            val visibleSpan=span/zoom
            val labelPaint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color=android.graphics.Color.rgb(35,54,77); textSize=10.sp.toPx()
            }
            val tickPaint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color=android.graphics.Color.rgb(117,130,148); textSize=10.sp.toPx(); textAlign=android.graphics.Paint.Align.CENTER
            }
            for(i in 0..4) {
                val x=left+plotWidth*i/4
                drawLine(Color(0xFFE8ECF1),androidx.compose.ui.geometry.Offset(x,axisY),androidx.compose.ui.geometry.Offset(x,size.height),strokeWidth=1.dp.toPx())
                val tick=(visibleStart+visibleSpan*i/4).toInt()
                drawContext.canvas.nativeCanvas.drawText(year(tick),x,14.dp.toPx(),tickPaint)
            }
            clipRect(left,axisY,size.width-right,size.height) {
            entries.forEachIndexed { i,e ->
                val cy=axisY+12.dp.toPx()+laneHeight.toPx()*i+laneHeight.toPx()*.5f
                drawLine(Color(0xFFF0F2F5),androidx.compose.ui.geometry.Offset(left,cy),androidx.compose.ui.geometry.Offset(size.width-right,cy),strokeWidth=1.dp.toPx())
                val startYear=(e.start?:low.toInt()).toFloat()
                val endYear=(e.end?:e.start?:low.toInt()).toFloat()
                val x1=left+((startYear-low)/span)*contentWidth-pan
                val x2=left+((endYear-low)/span)*contentWidth-pan
                val pulsing=e.pageId==pulsePageId && pulseOn
                if(e.start!=null && e.end!=null && e.end!=e.start) {
                    if(pulsing) drawLine(e.color.copy(alpha=.28f),androidx.compose.ui.geometry.Offset(minOf(x1,x2),cy),androidx.compose.ui.geometry.Offset(maxOf(x1,x2),cy),strokeWidth=18.dp.toPx())
                    drawLine(e.color,androidx.compose.ui.geometry.Offset(minOf(x1,x2),cy),androidx.compose.ui.geometry.Offset(maxOf(x1,x2),cy),strokeWidth=if(pulsing) 11.dp.toPx() else 8.dp.toPx())
                    drawCircle(e.color,if(pulsing) 6.dp.toPx() else 4.dp.toPx(),androidx.compose.ui.geometry.Offset((x1+x2)/2,cy))
                }
                drawCircle(e.color,if(pulsing) 9.dp.toPx() else 6.dp.toPx(),androidx.compose.ui.geometry.Offset(x1,cy))
            }
            }
            entries.forEachIndexed { i,e ->
                val cy=axisY+12.dp.toPx()+laneHeight.toPx()*i+laneHeight.toPx()*.5f
                drawContext.canvas.nativeCanvas.drawText(e.title.take(13),4.dp.toPx(),cy+4.dp.toPx(),labelPaint)
            }
            selectedYear?.let { selected ->
                val x=left+((selected-low)/span)*contentWidth-pan
                if(x>=left && x<=size.width-right) {
                    drawLine(Color(0xFF23364D).copy(alpha=.85f),androidx.compose.ui.geometry.Offset(x,axisY),androidx.compose.ui.geometry.Offset(x,size.height),strokeWidth=1.5.dp.toPx())
                    drawCircle(Color.White,5.dp.toPx(),androidx.compose.ui.geometry.Offset(x,axisY+2.dp.toPx()))
                    val text=year(selected)
                    val p=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color=android.graphics.Color.WHITE; textSize=11.sp.toPx(); textAlign=android.graphics.Paint.Align.CENTER; typeface=android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.NORMAL) }
                    val cx=x.coerceIn(34.dp.toPx(),size.width-34.dp.toPx())
                    drawContext.canvas.nativeCanvas.drawRoundRect(cx-32.dp.toPx(),2.dp.toPx(),cx+32.dp.toPx(),20.dp.toPx(),8.dp.toPx(),8.dp.toPx(),android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color=android.graphics.Color.rgb(35,54,77) })
                    drawContext.canvas.nativeCanvas.drawText(text,cx,14.dp.toPx(),p)
                }
            }
        }
        if(zoom>1f) {
            Row(Modifier.fillMaxWidth().padding(horizontal=12.dp, vertical=2.dp),verticalAlignment=Alignment.CenterVertically) {
                Text("←",fontSize=14.sp,color=Muted)
                Slider(value=panFraction,onValueChange={panFraction=it},modifier=Modifier.weight(1f),valueRange=0f..1f)
                Text("→",fontSize=14.sp,color=Muted)
            }
        }
        if(compact) Text("Компактный график · уменьшенные интервалы между сущностями",Modifier.padding(start=10.dp,bottom=3.dp),fontSize=10.sp,color=Muted)
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
    private fun json(url:String):JSONObject {
        val c=URL(url).openConnection() as HttpURLConnection
        c.connectTimeout=20000
        c.readTimeout=30000
        c.setRequestProperty("User-Agent","WikiGraphAndroid/0.2 (historical timeline)")
        return try { JSONObject(c.inputStream.bufferedReader().use { it.readText() }) } finally { c.disconnect() }
    }

    private fun entry(title:String,pageId:Int,description:String=""):Entry =
        Entry(title,android.text.Html.fromHtml(description.replace(Regex("<.*?>"),""),android.text.Html.FROM_HTML_MODE_COMPACT).toString(),pageId,
            url="https://ru.wikipedia.org/wiki/"+URLEncoder.encode(title.replace(' ','_'),"UTF-8").replace("+","%20"))

    fun search(q:String):List<Entry> {
        val s=URLEncoder.encode(q,"UTF-8")
        val a=json("https://ru.wikipedia.org/w/api.php?action=query&list=search&srsearch="+s+"&srnamespace=0&srlimit=20&format=json&utf8=1")
            .getJSONObject("query").getJSONArray("search")
        return (0 until a.length()).map { i -> val o=a.getJSONObject(i); entry(o.getString("title"),o.getInt("pageid"),o.optString("snippet")) }
    }

    fun searchCategories(prefix:String):List<CategoryOption> {
        val s=URLEncoder.encode(prefix.trim(),"UTF-8")
        val a=json("https://ru.wikipedia.org/w/api.php?action=query&list=allcategories&acprefix="+s+"&aclimit=30&acprop=size&format=json&utf8=1")
            .getJSONObject("query").getJSONArray("allcategories")
        return (0 until a.length()).map { i -> val o=a.getJSONObject(i); CategoryOption(o.optString("*"),o.optInt("size")) }
    }

    fun subcategories(category:String):List<CategoryOption> {
        val title=URLEncoder.encode("Категория:"+category,"UTF-8")
        val a=json("https://ru.wikipedia.org/w/api.php?action=query&list=categorymembers&cmtitle="+title+"&cmtype=subcat&cmlimit=50&format=json&utf8=1")
            .getJSONObject("query").optJSONArray("categorymembers") ?: return emptyList()
        return (0 until a.length()).map { i ->
            CategoryOption(a.getJSONObject(i).optString("title").removePrefix("Категория:").removePrefix("Category:"))
        }.sortedBy { it.title.lowercase(Locale.ROOT) }
    }

    fun searchInCategory(category:String):List<Entry> {
        val title=URLEncoder.encode("Категория:"+category,"UTF-8")
        val pages=json("https://ru.wikipedia.org/w/api.php?action=query&generator=categorymembers&gcmtitle="+title+"&gcmtype=page&gcmnamespace=0&gcmlimit=40&prop=extracts&exintro=1&explaintext=1&exchars=240&format=json&utf8=1")
            .getJSONObject("query").optJSONObject("pages") ?: return emptyList()
        val out=mutableListOf<Entry>()
        val keys=pages.keys()
        while(keys.hasNext()) { val p=pages.getJSONObject(keys.next()); out.add(entry(p.optString("title"),p.optInt("pageid"),p.optString("extract"))) }
        return out.sortedBy { it.title.lowercase(Locale.ROOT) }
    }

    fun recommend(seed:Entry,radius:Int):List<Entry> {
        val center=seed.start ?: seed.dateOptions.getOrNull(seed.selectedDateOption)?.start
            ?: seed.dateOptions.firstNotNullOfOrNull { it.start } ?: return emptyList()
        val years=(center-radius..center+radius).toList()
        val temporal=mutableListOf<Entry>()
        // MediaWiki search understands OR expressions; search the whole window in one request.
        runCatching {
            val expression=years.joinToString(" OR ") { "\"$it\"" }
            val encoded=URLEncoder.encode(expression,"UTF-8")
            val a=json("https://ru.wikipedia.org/w/api.php?action=query&list=search&srsearch="+encoded+
                "&srnamespace=0&srlimit=50&format=json&utf8=1").getJSONObject("query").getJSONArray("search")
            for(i in 0 until a.length()) {
                val o=a.getJSONObject(i)
                temporal.add(entry(o.optString("title"),o.optInt("pageid"),o.optString("snippet")))
            }
        }
        val seedCats=runCatching {
            val title=URLEncoder.encode(seed.title,"UTF-8")
            val pages=json("https://ru.wikipedia.org/w/api.php?action=query&prop=categories&cllimit=50&clshow=!hidden&titles="+title+"&format=json&utf8=1")
                .getJSONObject("query").getJSONObject("pages")
            val keys=pages.keys()
            if(!keys.hasNext()) emptyList() else {
                val cats=pages.getJSONObject(keys.next()).optJSONArray("categories") ?: org.json.JSONArray()
                (0 until cats.length()).map { cats.getJSONObject(it).optString("title").removePrefix("Категория:").removePrefix("Category:") }
                    .filter { !it.startsWith("Статьи с ") && !it.startsWith("Википедия:") &&
                        !it.startsWith("Pages with ") && !it.startsWith("Wikipedia:") }
            }
        }.getOrDefault(emptyList())
        val related=mutableListOf<Entry>()
        seedCats.take(6).forEach { cat ->
            runCatching {
                val title=URLEncoder.encode("Категория:"+cat,"UTF-8")
                val pages=json("https://ru.wikipedia.org/w/api.php?action=query&generator=categorymembers&gcmtitle="+title+
                    "&gcmtype=page&gcmnamespace=0&gcmlimit=30&prop=extracts&exintro=1&explaintext=1&exchars=500&format=json&utf8=1")
                    .getJSONObject("query").optJSONObject("pages")
                if(pages!=null) {
                    val keys=pages.keys()
                    while(keys.hasNext()) {
                        val p=pages.getJSONObject(keys.next())
                        related.add(entry(p.optString("title"),p.optInt("pageid"),p.optString("extract")))
                    }
                }
            }
        }
        val candidates=(related+temporal).distinctBy { it.pageId }
            .filter { it.pageId!=seed.pageId && it.title!=seed.title }
        // Search snippets are short, so fetch a single batch of article introductions before
        // scoring. This catches event years that do not appear in the search result snippet.
        val enrichedCandidates=runCatching {
            val ids=candidates.take(50).joinToString("|") { it.pageId.toString() }
            if(ids.isBlank()) emptyMap<Int,String>() else {
                val data=json("https://ru.wikipedia.org/w/api.php?action=query&prop=extracts&pageids="+ids+
                    "&exintro=1&explaintext=1&exchars=1800&format=json&utf8=1")
                    .getJSONObject("query").optJSONObject("pages") ?: JSONObject()
                val found=mutableMapOf<Int,String>()
                val keys=data.keys()
                while(keys.hasNext()) {
                    val page=data.getJSONObject(keys.next())
                    found[page.optInt("pageid")]=page.optString("extract")
                }
                found
            }
        }.getOrDefault(emptyMap())
        val candidatesWithText=candidates.map { c ->
            val extract=enrichedCandidates[c.pageId].orEmpty()
            if(extract.isBlank() || c.description.length>extract.length) c else c.copy(description=extract)
        }
        val yearPattern=Regex("(?<!\\d)(\\d{1,4})(?!\\d)")
        val ranked=candidatesWithText.map { candidate ->
            val text=(candidate.title+" "+candidate.description).replace(Regex("<.*?>")," ")
            val mentioned=yearPattern.findAll(text).mapNotNull { it.groupValues[1].toIntOrNull() }
                .filter { it in (center-radius)..(center+radius) }.toList()
            val titleYears=yearPattern.findAll(candidate.title).mapNotNull { it.groupValues[1].toIntOrNull() }
                .filter { it in (center-radius)..(center+radius) }.toList()
            val relatedBonus=if(related.any { it.pageId==candidate.pageId }) 2 else 0
            val yearScore=mentioned.distinct().size*3 + titleYears.distinct().size*5
            Triple(candidate,yearScore+relatedBonus,mentioned.minOfOrNull { kotlin.math.abs(it-center) } ?: radius+1)
        }
        // Prefer articles whose title/snippet explicitly mentions a year in the selected window,
        // then related category members. Avoid flooding the user with unrelated category pages.
        return ranked.filter { it.second>0 }
            .sortedWith(compareByDescending<Triple<Entry,Int,Int>> { it.second }.thenBy { it.third }.thenBy { it.first.title })
            .map { it.first }.take(35)
    }

    private fun articleDateOptions(text:String):List<DateOption> {
        val result=mutableListOf<DateOption>()
        val normalized=text.replace('\u00a0',' ').replace('\u2212','-')
        val bc="(?:до\\s*н\\.?\\s*э\\.?|до\\s+нашей\\s+эры)"
        val rangeRegex=Regex("(?<!\\d)(\\d{1,4})\\s*(?:гг?\\.?|года|год|—|–|-)\\s*(\\d{1,4})(\\s*$bc)?",RegexOption.IGNORE_CASE)
        val covered=mutableSetOf<IntRange>()
        rangeRegex.findAll(normalized).forEach { m ->
            val a=m.groupValues[1].toIntOrNull()
            val b=m.groupValues[2].toIntOrNull()
            if(a!=null && b!=null && a in 1..9999 && b in 1..9999) {
                val isBc=m.groupValues[3].isNotBlank()
                val start=if(isBc) -a else a
                val end=if(isBc) -b else b
                val context=normalized.substring((m.range.first-52).coerceAtLeast(0),(m.range.last+53).coerceAtMost(normalized.length))
                    .replace(Regex("\\s+")," ").trim().take(100)
                result.add(DateOption("Из статьи: $context",start,end))
                covered.add(m.range)
            }
        }
        val yearRegex=Regex("(?<!\\d)(\\d{1,4})(?:\\s*(года|год|г\\.?))?(\\s*$bc)?",RegexOption.IGNORE_CASE)
        yearRegex.findAll(normalized).forEach { m ->
            if(covered.any { it.first<=m.range.first && it.last>=m.range.last }) return@forEach
            val value=m.groupValues[1].toIntOrNull() ?: return@forEach
            if(value !in 1..9999) return@forEach
            val before=normalized.substring((m.range.first-16).coerceAtLeast(0),m.range.first)
            val after=normalized.substring(m.range.last+1,(m.range.last+28).coerceAtMost(normalized.length))
            val hasDateMarker=m.groupValues[2].isNotBlank() || m.groupValues[3].isNotBlank() ||
                Regex("\\b(?:в|с|к|около|примерно|начало|конец|середина|июня|июля|августа|сентября|октября|ноября|декабря|января|февраля|марта|апреля|мая)\\s*$",RegexOption.IGNORE_CASE).containsMatchIn(before) ||
                Regex("^\\s*(?:году|года|г\\.|до\\s)",RegexOption.IGNORE_CASE).containsMatchIn(after)
            if(value<100 && !hasDateMarker) return@forEach
            val isBc=m.groupValues[3].isNotBlank()
            val year=if(isBc) -value else value
            val context=normalized.substring((m.range.first-48).coerceAtLeast(0),(m.range.last+49).coerceAtMost(normalized.length))
                .replace(Regex("\\s+")," ").trim().take(94)
            result.add(DateOption("Из статьи: $context",year,null))
        }
        return result.distinctBy { Triple(it.label,it.start,it.end) }
    }

    fun enrich(e:Entry):Entry {
        val q=URLEncoder.encode(e.title,"UTF-8")
        val pageData=runCatching {
            val response=json("https://ru.wikipedia.org/w/api.php?action=query&prop=pageprops%7Cextracts%7Ccategories&clshow=%21hidden&cllimit=max&ppprop=wikibase_item&explaintext=1&titles="+q+"&format=json")
            val pages=response.getJSONObject("query").getJSONObject("pages")
            val key=pages.keys().asSequence().firstOrNull()
            if(key==null) null else pages.getJSONObject(key)
        }.getOrNull()
        val articleText=pageData?.optString("extract").orEmpty()
        val categories=pageData?.optJSONArray("categories")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).optString("title").removePrefix("Категория:").removePrefix("Category:") }.distinct().sortedBy { it.lowercase(Locale.ROOT) } }.orEmpty()
        val wd=pageData?.optJSONObject("pageprops")?.optString("wikibase_item").orEmpty().let { if(it=="null") "" else it }
        val options=mutableListOf<DateOption>()
        var birth:Int?=null
        var death:Int?=null
        var starts=listOf<Int>()
        var ends=listOf<Int>()
        var inception=listOf<Int>()
        var dissolved=listOf<Int>()
        if(wd.isNotBlank()) runCatching {
            val entity=json("https://www.wikidata.org/wiki/Special:EntityData/"+wd+".json")
                .getJSONObject("entities").getJSONObject(wd)
            val claims=entity.optJSONObject("claims") ?: JSONObject()
            fun years(property:String):List<Int> {
                val a=claims.optJSONArray(property) ?: return emptyList()
                return (0 until a.length()).mapNotNull { i ->
                    val value=a.getJSONObject(i).optJSONObject("mainsnak")?.optJSONObject("datavalue")
                        ?.optJSONObject("value")?.optString("time") ?: return@mapNotNull null
                    Regex("^([+-]?\\d{1,16})").find(value)?.groupValues?.get(1)?.toIntOrNull()
                }.distinct()
            }
            fun addProperty(property:String,label:String) {
                val values=years(property)
                values.forEachIndexed { i,y ->
                    options.add(DateOption(if(values.size>1) "$label · "+(i+1) else label,y,null))
                }
            }
            birth=years("P569").firstOrNull()
            death=years("P570").firstOrNull()
            starts=years("P580")
            ends=years("P582")
            inception=years("P571")
            dissolved=years("P576")
            addProperty("P569","Рождение")
            addProperty("P570","Смерть")
            addProperty("P585","Дата события")
            addProperty("P576","Прекращение")
            addProperty("P580","Начало периода")
            addProperty("P582","Окончание периода")
            addProperty("P571","Основание / создание")
        }
        starts.forEachIndexed { i,y -> options.add(DateOption("Период "+(i+1),y,ends.getOrNull(i) ?: ends.firstOrNull())) }
        inception.forEachIndexed { i,y -> options.add(DateOption("Существование "+(i+1),y,dissolved.getOrNull(i) ?: dissolved.firstOrNull())) }
        options.addAll(articleDateOptions(articleText))
        val primaryStart=starts.firstOrNull() ?: inception.firstOrNull() ?: birth ?: options.firstOrNull()?.start
        val primaryEnd=ends.firstOrNull() ?: dissolved.firstOrNull() ?: death
        if(primaryStart!=null && options.none { it.start==primaryStart && it.end==primaryEnd }) {
            options.add(0,DateOption("Основной период",primaryStart,primaryEnd))
        }
        val uniqueOptions=options.distinctBy { Triple(it.label,it.start,it.end) }
        val defaultIndex=uniqueOptions.indexOfFirst { it.start==primaryStart && it.end==primaryEnd }.coerceAtLeast(0)
        val kind=when {
            birth!=null || death!=null -> "Человек"
            starts.isNotEmpty() || ends.isNotEmpty() || inception.isNotEmpty() -> "Событие"
            else -> e.kind
        }
        val wikidataDescription=if(wd.isNotBlank()) runCatching {
            val entity=json("https://www.wikidata.org/wiki/Special:EntityData/"+wd+".json")
                .getJSONObject("entities").getJSONObject(wd)
            entity.optJSONObject("descriptions")?.optJSONObject("ru")?.optString("value").orEmpty()
        }.getOrDefault("") else ""
        // Prefer the Wikipedia article introduction for richer details; fall back to Wikidata.
        val desc=articleText.ifBlank { wikidataDescription }.ifBlank { e.description }
        return e.copy(description=desc,wd=wd,start=primaryStart,end=primaryEnd,kind=kind,dateOptions=uniqueOptions,selectedDateOption=defaultIndex,categories=categories)
    }
}
