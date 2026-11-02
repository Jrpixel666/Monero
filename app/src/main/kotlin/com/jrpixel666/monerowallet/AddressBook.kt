package com.jrpixel666.monerowallet

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * A local, labeled address book. Addresses and labels are not secret the way a spend key is —
 * they're already public once shared — so this is a plain JSON file in app-private storage, not
 * an `storage::encrypt`-protected blob; app-private storage already keeps it off other apps'
 * reach (THREAT_MODEL.md #1), which is the threat that actually applies here.
 */
class AddressBook(context: Context) {
  data class Entry(val label: String, val address: String)

  private val file = File(context.filesDir, "address_book.json")

  fun all(): List<Entry> {
    if (!file.exists()) return emptyList()
    val array = JSONArray(file.readText())
    return (0 until array.length()).map { i ->
      val obj = array.getJSONObject(i)
      Entry(obj.getString("label"), obj.getString("address"))
    }
  }

  fun add(label: String, address: String) {
    val updated = all().filterNot { it.label == label } + Entry(label, address)
    val array = JSONArray()
    for (entry in updated) {
      array.put(JSONObject().put("label", entry.label).put("address", entry.address))
    }
    file.writeText(array.toString())
  }

  fun remove(label: String) {
    val updated = all().filterNot { it.label == label }
    val array = JSONArray()
    for (entry in updated) {
      array.put(JSONObject().put("label", entry.label).put("address", entry.address))
    }
    file.writeText(array.toString())
  }
}
