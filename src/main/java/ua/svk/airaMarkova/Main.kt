package ua.svk.airaMarkova

import com.google.gson.JsonParser
import com.vk.api.sdk.client.VkApiClient
import com.vk.api.sdk.client.actors.GroupActor
import com.vk.api.sdk.httpclient.HttpTransportClient
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.util.*
import kotlin.collections.HashMap

val commands = HashMap<String, (String, Int) -> Unit>()
val jsonParser = JsonParser()
val data = Paths.get("data.txt")
val settings = Paths.get("settings.txt")
var counter = 0
val regexCommand = Regex("Aira \\/")
val rand = Random()

var keySize = 1
var messageInterval = 10
var outputWordsMax = 20
var groupId = 0
lateinit var token: String

lateinit var vk: VkApiClient
lateinit var gActor: GroupActor

fun main(args: Array<String>)
{
    loadSettings()
    initCommands()
    initVk()
}

fun initCommands()
{
    commands["setKeySize"] = { text, cId ->
        keySize = text.toInt()
    }

    commands["setMessageInterval"] = { text, cId ->
        messageInterval = text.toInt()
    }

    commands["setOutputWordsMax"] = { text, cId ->
        outputWordsMax = text.toInt()
    }

    commands["g"] = { text, cId ->
        sendMessage(markov(), cId)
    }

    commands["info"] = { text, cId ->
        sendMessage("Database: ${String(Files.readAllBytes(data)).split(" ").size} words\n" +
                "keySize: $keySize\n" +
                "outputWordsMax: $outputWordsMax\n" +
                "messageInterval: $messageInterval", cId)
    }

    commands["help"] = { text, cId ->
        sendMessage(commands.keys.joinToString(", "), cId)
    }
}

fun loadSettings()
{
    String(Files.readAllBytes(settings), Charsets.UTF_8).split(",").forEach {
        val data = it.split("=")
        when(data[0])
        {
            "keySize" -> keySize = data[1].toInt()
            "messageInterval" -> messageInterval = data[1].toInt()
            "outputWordsMax" -> outputWordsMax = data[1].toInt()
            "groupId" -> groupId = data[1].toInt()
            "token" -> token = data[1]
        }
    }
}

fun saveSettings()
{
    Files.write(settings, ("keySize=$keySize,messageInterval=$messageInterval,outputWordsMax=$outputWordsMax,groupId=$groupId,token=$token").toByteArray(Charsets.UTF_8), StandardOpenOption.TRUNCATE_EXISTING)
}

fun initVk()
{
    try
    {
        vk = VkApiClient(HttpTransportClient())
        gActor = GroupActor(groupId, token)

        var lps = vk.groups().getLongPollServer(gActor).execute()
        var lastUpdate = lps.ts

        while (true)
        {
            val response = jsonParser.parse(vk.transportClient.get("${lps.server}?act=a_check&key=${lps.key}&ts=${lastUpdate}&wait=25").content).asJsonObject
            if (response.has("failed") && response.get("failed").asInt == 2)
            {
                lps = vk.groups().getLongPollServer(gActor).execute()
            }
            else
            {
                lastUpdate = response.get("ts").asInt

                response.get("updates").asJsonArray.forEach {
                    val obj = it.asJsonObject
                    val type = obj.get("type").asString

                    if (type == "message_new")
                    {
                        val mObj = obj.get("object").asJsonObject

                        val cId = mObj.get("peer_id").asInt
                        val text = mObj.get("text").asString

                        if (text.contains(regexCommand))
                            processCommand(text.replace(regexCommand, ""), cId)
                        else
                            processMessage(text, cId)
                    }
                }
            }
        }
    }
    catch (t: Throwable)
    {
        t.printStackTrace()
        Thread.sleep(10000)
        initVk()
    }
}

fun processCommand(text: String, cId: Int)
{
    try
    {
        text.split(" ").also {
            commands[it[0]]!!
                    .invoke(it.drop(1).joinToString(" "), cId)
        }

        saveSettings()
    }
    catch (t: Throwable)
    {
        t.printStackTrace()
    }
}

fun processMessage(text: String, cId: Int)
{
    val text = text.replace("\n", " ")
            .plus(" ")
            .replace("  ", " ")

    Files.write(data, text.toByteArray(Charsets.UTF_8), StandardOpenOption.APPEND)

    if(++counter == messageInterval)
    {
        counter = 0
        sendMessage(markov(), cId)
    }
}

fun sendMessage(text: String, cId: Int)
{
    vk.messages().send(gActor)
            .peerId(cId)
            .message(text)
            .execute()
}

fun markov(): String
{
    return Markov.markov(data, keySize, keySize+rand.nextInt(outputWordsMax-keySize))
            .trim()
            .toCharArray()
            .also { it[0] = it[0].toUpperCase()}
            .also { if(it.last() == ',') it[it.lastIndex] = '.' }
            .joinToString("")
            .let { if(it.last() != '.') "$it." else it }
}