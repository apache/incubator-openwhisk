/*
 * Copyright 2015-2017 IBM Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package whisk.core.entity

import spray.json.DefaultJsonProtocol
import whisk.core.database.DocumentFactory
import spray.json._

/**
 * WhiskTriggerPut is a restricted WhiskTrigger view that eschews properties
 * that are auto-assigned or derived from URI: namespace and name.
 */
case class WhiskTriggerPut(
    parameters: Option[Parameters] = None,
    limits: Option[TriggerLimits] = None,
    version: Option[SemVer] = None,
    publish: Option[Boolean] = None,
    annotations: Option[Parameters] = None)

/**
 * Representation of a rule to be stored inside a trigger. Contains all
 * information needed to be able to determine if and which action is to
 * be fired.
 *
 * @param action the fully qualified name of the action to be fired
 * @param status status of the rule
 */
case class ReducedRule(
    action: FullyQualifiedEntityName,
    status: Status)

/**
 * A WhiskTrigger provides an abstraction of the meta-data
 * for a whisk trigger.
 *
 * The WhiskTrigger object is used as a helper to adapt objects between
 * the schema used by the database and the WhiskTrigger abstraction.
 *
 * @param namespace the namespace for the trigger
 * @param name the name of the trigger
 * @param parameters the set of parameters to bind to the trigger environment
 * @param limits the limits to impose on the trigger
 * @param version the semantic version
 * @param publish true to share the action or false otherwise
 * @param annotation the set of annotations to attribute to the trigger
 * @param rules the map of the rules that are associated with this trigger. Key is the rulename and value is the ReducedRule
 * @throws IllegalArgumentException if any argument is undefined
 */
@throws[IllegalArgumentException]
case class WhiskTrigger(
    namespace: EntityPath,
    override val name: EntityName,
    parameters: Parameters = Parameters(),
    limits: TriggerLimits = TriggerLimits(),
    version: SemVer = SemVer(),
    publish: Boolean = false,
    annotations: Parameters = Parameters(),
    rules: Option[Map[FullyQualifiedEntityName, ReducedRule]] = None)
    extends WhiskEntity(name) {

    require(limits != null, "limits undefined")

    def toJson = WhiskTrigger.serdes.write(this).asJsObject

    def withoutRules = WhiskTrigger(namespace, name, parameters, limits, version, publish, annotations, None)

    /**
     * Inserts the rulename, its status and the action to be fired into the trigger.
     *
     * @param rulename The fully qualified name of the rule, that will be fired by this trigger.
     * @param rule The rule, that will be fired by this trigger. It's from type ReducedRule. This type
     * contains the fully qualified name of the action to be fired by the rule and the status of the rule.
     */
    def addRule(rulename: FullyQualifiedEntityName, rule: ReducedRule) = {
        val entry = rulename -> rule
        val links = rules getOrElse Map[FullyQualifiedEntityName, ReducedRule]()
        WhiskTrigger(namespace, name, parameters, limits, version, publish, annotations, Some(links + entry)).revision[WhiskTrigger](docinfo.rev)
    }

    /**
     * Removes the rule from the trigger.
     *
     * @param rule The fully qualified name of the rule, that should be removed from the
     * trigger. After removing the rule, it won't be fired anymore by this trigger.
     */
    def removeRule(rule: FullyQualifiedEntityName) = {
        WhiskTrigger(namespace, name, parameters, limits, version, publish, annotations, rules.map(_ - rule)).revision[WhiskTrigger](docinfo.rev)
    }
}

object ReducedRule extends DefaultJsonProtocol {
    private implicit val fqnSerdes = FullyQualifiedEntityName.serdes
    implicit val serdes = jsonFormat2(ReducedRule.apply)
}

object WhiskTrigger
    extends DocumentFactory[WhiskTrigger]
    with WhiskEntityQueries[WhiskTrigger]
    with DefaultJsonProtocol {

    override val collectionName = "triggers"

    private implicit val fqnSerdesAsDocId = FullyQualifiedEntityName.serdesAsDocId
    override implicit val serdes = jsonFormat8(WhiskTrigger.apply)

    override val cacheEnabled = true
    override def cacheKeyForUpdate(w: WhiskTrigger) = w.docid.asDocInfo
}

object WhiskTriggerPut extends DefaultJsonProtocol {
    implicit val serdes = jsonFormat5(WhiskTriggerPut.apply)
}
