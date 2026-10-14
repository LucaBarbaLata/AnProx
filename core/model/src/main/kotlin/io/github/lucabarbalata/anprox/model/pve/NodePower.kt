package io.github.lucabarbalata.anprox.model.pve

/**
 * `POST /nodes/{node}/status` with `command=reboot|shutdown`. Both stop every guest on the node
 * (HA moves its own), so both are type-to-confirm (questionnaire Q12).
 */
enum class NodePowerAction(val apiName: String) {
    REBOOT("reboot"),
    SHUTDOWN("shutdown"),
    ;

    val danger: DangerLevel get() = DangerLevel.TYPE_TO_CONFIRM
}
