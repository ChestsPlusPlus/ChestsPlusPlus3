package com.jamesdpeters.chestsplusplus.services.listeners

import com.jamesdpeters.chestsplusplus.*
import com.jamesdpeters.chestsplusplus.services.data.InventoryStorageService
import com.jamesdpeters.chestsplusplus.services.data.LocationStorageService
import com.jamesdpeters.chestsplusplus.services.logic.ChestLinkService
import com.jamesdpeters.chestsplusplus.spigot.event.LocationInfoLoadEvent
import com.jamesdpeters.chestsplusplus.storage.serializable.InventoryStore.Companion.inventoryStore
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.block.data.type.Chest
import org.bukkit.entity.ItemFrame
import org.bukkit.entity.Player
import org.bukkit.event.Cancellable
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.hanging.HangingBreakByEntityEvent
import org.bukkit.event.hanging.HangingBreakEvent
import org.bukkit.event.inventory.*
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.Inventory
import org.bukkit.persistence.PersistentDataType
import org.bukkit.util.Vector
import org.springframework.stereotype.Service

@Service
class StorageListener(
    private val chestLinkService: ChestLinkService,
    private val inventoryStorageService: InventoryStorageService,
    private val locationStorageService: LocationStorageService,
    private val itemFrameKey: NamespacedKey
) : SpringBukkitListener() {

    // ? Chest Link Inventory Events

    @EventHandler
    fun chestOpen(event: PlayerInteractEvent) {
        if (event.player.isSneaking || event.action != Action.RIGHT_CLICK_BLOCK)
            return

        event.clickedBlock?.let { block ->
            if (block.isChestLink) {
                inventoryStorageService.inventoryStoreAtLocation(block.location)?.let { invStore ->
                    event.isCancelled = true
                    chestLinkService.openChestInventory(event.player, invStore)
                }
            }
        }
    }

    @EventHandler
    fun closeInventory(event: InventoryCloseEvent) {
        event.inventory.holder.inventoryStore?.let { invStore ->
            event.inventory.viewers.remove(event.player)
            if (event.inventory.viewers.size == 0) {
                locationStorageService.getLocations(invStore.uuid).forEach {
                    it.location?.containerAnimation(false)
                }
            }
        }
    }

    // ? ChestLink Add/Remove events

    @EventHandler
    fun createChestLink(event: PlayerInteractEvent) {
        if (event.player.isSneaking && event.action == Action.RIGHT_CLICK_BLOCK) {
            event.item?.let { item ->
                item.itemMeta?.let { itemMeta ->
                    event.clickedBlock?.let { block ->
                        if (item.type == Material.NAME_TAG && block.isChestLink) {
                            chestLinkService.addChestLink(event.player, block.location, itemMeta.displayName)
                        }
                    }
                }
            }
        }
    }

    @EventHandler
    fun storageBreak(event: BlockBreakEvent) {
        if (event.block.isChestLink) {
            chestLinkService.removeChestLink(event.block.location)
        }
    }

    @EventHandler
    fun storageLoaded(event: LocationInfoLoadEvent) {
        Log.debug { "Loaded location: ${event.locationInfo.location}" }
        event.locationInfo.location?.also { location ->
            if (event.locationInfo.inventoryUUID == null)
                return

            location.block.blockData.directional?.also { chest ->
                val relativeBlock = location.block.getRelative(chest.facing)

                val itemFrame = relativeBlock.location.world?.spawn(relativeBlock.location, ItemFrame::class.java)
                itemFrame?.persistentDataContainer?.set(itemFrameKey, PersistentDataType.STRING, event.locationInfo.inventoryUUID.toString())
                itemFrame?.setFacingDirection(chest.facing)
                itemFrame?.isVisible = false

                event.locationInfo.itemFrame = itemFrame

                inventoryStorageService.inventoryStore(event.locationInfo.inventoryUUID!!)?.apply {
                    calculateMostCommonItem()
                    event.locationInfo.updateItemFrame(mostCommonItem)
                }

                locationStorageService.persistChunk(location.chunk)
                Log.debug { "Spawned item frame: $itemFrame" }
            }
        }
    }

    // ? ChestLink Inventory update events

    @EventHandler(priority = EventPriority.LOWEST)
    fun InventoryClickEvent.itemFrameUpdate() = itemFrameUpdate(inventory)

    @EventHandler
    fun InventoryDragEvent.itemFrameUpdate() = itemFrameUpdate(inventory)

    @EventHandler
    fun InventoryMoveItemEvent.itemFrameUpdate() {
        itemFrameUpdate(source)
        itemFrameUpdate(destination)
    }


    private fun itemFrameUpdate(inventory: Inventory) {
        inventory.holder.inventoryStore?.updateLocations(locationStorageService)
    }

    // ? ChestLink ItemFrame events

    @EventHandler
    fun itemFrameBreakByEntity(event: HangingBreakByEntityEvent) {
        event.entity.itemFrame?.let { itemFrame ->
            if (event.cause == HangingBreakEvent.RemoveCause.ENTITY) {
                itemFrameRemoveEvent(itemFrame, event, event.remover.player)
            }
        }
    }

    @EventHandler
    fun itemFrameDamageByEntity(event: EntityDamageByEntityEvent) {
        event.entity.itemFrame?.let { itemFrame ->
            itemFrameRemoveEvent(itemFrame, event, event.damager.player)
        }
    }

    private fun itemFrameRemoveEvent(itemFrame: ItemFrame, event: Cancellable, player: Player?) {
        // Check if the item frame has the PDC key
        if (itemFrame.persistentDataContainer.has(itemFrameKey, PersistentDataType.STRING)) {
            // Cancel the event to prevent the item frame from breaking
            event.isCancelled = true

            player?.let {
                // If a Player broke the ItemFrame and wasn't sneaking we should open the chest instead.
                if (!it.isSneaking) {
                    // Get the UUID stored in the item frame's persistent data container
                    itemFrame.persistentDataContainer[itemFrameKey, PersistentDataType.STRING]?.toUUID?.let { uuid ->
                        inventoryStorageService.inventoryStore(uuid)?.let { inv ->
                                chestLinkService.openChestInventory(it, inv)
                            }
                    }
                } else {
                    if (itemFrame.isGlowing) {
                        chestLinkService.removeChestLink(itemFrame.attachedFaceLocation)
                        return
                    }
                    itemFrame.isGlowing = true

                    Bukkit.getScheduler().scheduleSyncDelayedTask(ChestsPlusPlus.plugin(), {
                        itemFrame.isGlowing = false
                    }, 20*2)
                }
            }
        }
    }

    @EventHandler
    fun itemFrameInteract(event: PlayerInteractEntityEvent) {
        if (event.player.isSneaking) {
            event.isCancelled = true
            return
        }
        event.rightClicked.itemFrame?.let { itemFrame ->
            if (itemFrame.persistentDataContainer.has(itemFrameKey, PersistentDataType.STRING)) {
                itemFrame.attachedFaceLocation.let { location ->
                    event.isCancelled = true
                    inventoryStorageService.inventoryStoreAtLocation(location)?.let { inventoryStore ->
                        chestLinkService.openChestInventory(event.player, inventoryStore)
                    }
                }
            }
        }
    }

    // ? Double chest prevention
    @EventHandler(priority = EventPriority.LOWEST)
    fun BlockPlaceEvent.doubleChestPrevention() {
        if (block.isChestLink) {
            block.blockData.directional?.facing?.let {
                val rightOffset = Vector(-it.direction.z, 0.0, it.direction.x)
                val leftOffset = Vector(it.direction.z, 0.0, -it.direction.x)
                val rightLoc = block.location.add(rightOffset)
                val leftLoc = block.location.add(leftOffset)

                val convertChest = { loc: Location ->
                    val chest = loc.block.blockData
                    if (chest is Chest) {
                        chest.type = Chest.Type.SINGLE
                        rightLoc.block.blockData = chest

                        val newChest = block.blockData
                        if (newChest is Chest) {
                            newChest.type = Chest.Type.SINGLE
                            block.blockData = newChest
                        }
                    }
                }

                inventoryStorageService.inventoryStoreAtLocation(rightLoc)?.let {
                    convertChest(rightLoc)
                }

                inventoryStorageService.inventoryStoreAtLocation(leftLoc)?.let {
                    convertChest(leftLoc)
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun HopperInventorySearchEvent.searchForChestLink() {
        inventoryStorageService.inventoryStoreAtLocation(searchBlock.location)?.let {
            inventory = it.inventory
        }
    }

}