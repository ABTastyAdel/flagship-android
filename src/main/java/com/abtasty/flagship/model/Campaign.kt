package com.abtasty.flagship.model

import android.os.Parcelable
import com.abtasty.flagship.database.DatabaseManager
import com.abtasty.flagship.database.ModificationData
import com.abtasty.flagship.main.Flagship
import com.abtasty.flagship.utils.ETargetingComp
import com.abtasty.flagship.utils.Logger
import com.abtasty.flagship.utils.Utils
import org.json.JSONObject
import kotlinx.android.parcel.Parcelize
import kotlinx.android.parcel.RawValue
import org.json.JSONArray
import kotlin.Exception


@Parcelize
internal data class Campaign(
    var id: String,
    var variationGroups: HashMap<String, VariationGroup>
) : Parcelable {

    companion object {

        fun parse(jsonArray: JSONArray): HashMap<String, Campaign>? {
            return try {
                val result = HashMap<String, Campaign>()
                for (i in 0 until jsonArray.length()) {
                    val campaign = parse(jsonArray.getJSONObject(i))
                    campaign?.let {
                        result.put(campaign.id, campaign)
                    }
                }
                return result
            } catch (e: Exception) {
                Logger
                null
            }
        }

        fun parse(jsonObject: JSONObject): Campaign? {
            return try {
                val id = jsonObject.getString("id")
                val variationGroupsArr = jsonObject.optJSONArray("variationGroups")
                val variationGroups = HashMap<String, VariationGroup>()
                if (variationGroupsArr != null) {
                    for (i in 0 until variationGroupsArr.length()) {
                        val variationGroup =
                            VariationGroup.parse(variationGroupsArr.getJSONObject(i))
                        variationGroup?.let {
                            variationGroups.put(it.variationGroupId, it)
                        }
                    }
                } else {
                    System.out.println("#M no varGroupe parse : " + jsonObject)
                    val variationGroup = VariationGroup.parse(jsonObject)
                    variationGroup?.let {
                        variationGroups.put(it.variationGroupId, it)
                    }
                }
                Campaign(id, variationGroups)
            } catch (e: Exception) {
                Logger.e(Logger.TAG.PARSING, "[Campaign object parsing error]")
                null
            }

        }
    }

    fun getModifications(useBucketing: Boolean): HashMap<String, Modification> {
        if (!useBucketing)
            Flagship.modifications.clear() // Bucketing
        val result = HashMap<String, Modification>()
        for ((key, variationGroup) in variationGroups) {
            if (!useBucketing) {
                for (v in variationGroup.variations) {
                    val mod = v.value.modifications?.values
                    mod?.let { result.putAll(it) }
                }
            } else {
                val variationId = variationGroup.selectedVariationId
                if (variationGroup.isTargetingValid()) {
                    val variation = variationGroup.variations[variationId]
                    val mod = variation?.modifications?.values
                    mod?.let {
                        System.out.println("#M getModifications = $it")
                        result.putAll(it)
                    }
                    break
                }
            }
        }
        return result
    }
}

@Parcelize
internal data class VariationGroup(
    var variationGroupId: String,
    var variations: HashMap<String, Variation>,
    var targetingGroups: TargetingGroups? = null,
    var selectedVariationId: String? = null
) : Parcelable {

    companion object {

        fun parse(jsonObject: JSONObject): VariationGroup? {
            return try {
                val groupId = jsonObject.getString("id")
                var selectedVariationId = DatabaseManager.getInstance().getAllocation(
                    Flagship.visitorId ?: "",
                    Flagship.customVisitorId ?: "", groupId
                )
                val variations = HashMap<String, Variation>()
                val variationObj = jsonObject.optJSONObject("variation")
                if (variationObj != null) {
                    val variation = Variation.parse(groupId, variationObj)
                    variation.selected = true
                    selectedVariationId = variation.id
                    variations[variation.id] = variation
                    System.out.println("#M select variation = " + selectedVariationId)
                } else {
                    val variationArr = jsonObject.optJSONArray("variations")
                    if (variationArr != null) {
                        var p = 0
                        val random = Utils.getVisitorAllocation()
                        for (i in 0 until variationArr.length()) {
                            val variation = Variation.parse(groupId, variationArr.getJSONObject(i))
                            if (selectedVariationId == null) {
                                p += variation.allocation
                                if (random <= p) {
                                    selectedVariationId = variation.id

                                    variation.selected = true
                                    Logger.v(
                                        Logger.TAG.BUCKETING,
                                        "[Variation ${variation.id} selected][Allocation $random]"
                                    )
                                    DatabaseManager.getInstance().insertAllocation(
                                        Flagship.visitorId ?: "",
                                        Flagship.customVisitorId ?: "",
                                        variation.groupId,
                                        variation.id
                                    )
                                }
                            }
                            variations[variation.id] = variation
                        }
                    }
                }


                val targeting = jsonObject.optJSONObject("targeting")
                val targetingGroups =
                    if (targeting != null && targeting.has("targetingGroups"))
                        TargetingGroups.parse(targeting.getJSONArray("targetingGroups"))
                    else null
                VariationGroup(groupId, variations, targetingGroups, selectedVariationId)
            } catch (e: Exception) {
                Logger.e(Logger.TAG.PARSING, "[VariationGroup object parsing error]")
                e.printStackTrace()
                null
            }
        }
    }

    fun isTargetingValid(): Boolean {
        return targetingGroups?.isTargetingValid() ?: false
    }
}

@Parcelize
internal data class TargetingGroups(val targetingGroups: ArrayList<TargetingList>? = null) :
    Parcelable {

    companion object {
        fun parse(jsonArray: JSONArray): TargetingGroups? {
            return try {
                val targetingList = ArrayList<TargetingList>()
                for (i in 0 until jsonArray.length()) {
                    val targeting = TargetingList.parse(jsonArray.getJSONObject(i))
                    targeting?.let { targetingList.add(it) }
                }
                return TargetingGroups(targetingList)
            } catch (e: Exception) {
                Logger.e(Logger.TAG.PARSING, "[TargetingGroups object parsing error]")
                null
            }
        }
    }

    fun isTargetingValid(): Boolean {
        targetingGroups?.let {
            for (t in it) {
                if (t.isTargetingValid()) {
                    return true
                }
            }
        }
        return false
    }
}

@Parcelize
internal data class TargetingList(val targetings: ArrayList<Targeting>? = null) : Parcelable {

    companion object {
        fun parse(jsonObject: JSONObject): TargetingList? {
            return try {
                val targetings = ArrayList<Targeting>()
                val jsonArray = jsonObject.getJSONArray("targetings")
                for (i in 0 until jsonArray.length()) {
                    val targeting = Targeting.parse(jsonArray.getJSONObject(i))
                    targeting?.let { targetings.add(it) }
                }
                TargetingList(targetings)
            } catch (e: Exception) {
                Logger.e(Logger.TAG.PARSING, "[Targetings object parsing error]")
                null
            }
        }
    }

    fun isTargetingValid(): Boolean {
        targetings?.let {
            for (t in it) {
                if (!t.isTargetingValid()) {
                    return false
                }
            }
        }
        return true
    }
}

@Parcelize
internal data class Targeting(val key: String, val value: @RawValue Any, val operator: String) :
    Parcelable {

    companion object {
        fun parse(jsonObject: JSONObject): Targeting? {
            return try {
                val key = jsonObject.getString("key")
                val value = jsonObject.get("value")
                val operator = jsonObject.getString("operator")
                Targeting(key, value, operator)
            } catch (e: Exception) {
                Logger.e(Logger.TAG.PARSING, "[Targeting object parsing error]")
                null
            }

        }
    }

    fun isTargetingValid() : Boolean {

        val value0 = Flagship.context[key]
        val value1 = value
        return if (value0 == null) false else (ETargetingComp.get(operator)?.compare(value0, value1) ?: false)
    }
}

@Parcelize
internal data class Variation(
    val groupId: String,
    val id: String,
    val modifications: Modifications?,
    val allocation: Int = 100,
    var selected: Boolean = false
) : Parcelable {

    companion object variations {

        fun parse(groupId: String, jsonObject: JSONObject): Variation {
            val id = jsonObject.getString("id")
            val modifications =
                Modifications.parse(groupId, id, jsonObject.getJSONObject("modifications"))
            val allocation = jsonObject.optInt("allocation", -1)
            return Variation(groupId, id, modifications, allocation)
        }
    }
}

@Parcelize
internal data class Modifications(
    val variationGroupId: String,
    val variationId: String,
    val type: String,
    val values: HashMap<String, Modification>
) : Parcelable {
    companion object {

        fun parse(
            variationGroupId: String,
            variationId: String,
            jsonObject: JSONObject
        ): Modifications {
            return try {
                val type = jsonObject.getString("type")
                val values = HashMap<String, Modification>()
                val valueObj = jsonObject.getJSONObject("value")
                for (k in valueObj.keys()) {
                    val value = valueObj.get(k)
                    if (value is Boolean || value is Number || value is String) {
                        values[k] = Modification(k, variationGroupId, variationId, value)
                    } else {
                        Logger.e(
                            Logger.TAG.PARSING,
                            "Modification parsing : variationGroupId = $variationGroupId, variationId = $variationId\" : Your data \"$k\" is not a type of NUMBER, BOOLEAN or STRING"
                        )
                    }

                }
                Modifications(variationGroupId, variationId, type, values)
            } catch (e: Exception) {
                e.printStackTrace()
                Logger.e(Logger.TAG.PARSING, "variationGroupId = $variationGroupId, variationId = $variationId")
                Modifications("", "", "", HashMap())
            }
        }
    }
}

@Parcelize
data class Modification(
    val key: String,
    val variationGroupId: String,
    val variationId: String,
    val value: @RawValue Any
) : Parcelable {

    fun toModificationData(): ModificationData {
        val json = JSONObject().put(key, value)
        return ModificationData(
            key, Flagship.visitorId ?: "", Flagship.customVisitorId ?: "",
            variationGroupId, variationId, json
        )
    }

    companion object {
        fun fromModificationData(modification: ModificationData): Modification {
            return Modification(
                modification.key,
                modification.variationGroupId,
                modification.variationId,
                modification.value.get(modification.key)
            )
        }
    }
}