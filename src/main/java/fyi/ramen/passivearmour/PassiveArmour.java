package fyi.ramen.passivearmour;

import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.api.ModInitializer;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.EquippableComponent;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.item.equipment.EquipmentAsset;
import net.minecraft.item.equipment.EquipmentAssetKeys;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class PassiveArmour implements ModInitializer {
    // Constants
    public static final String MOD_ID = "passivearmour";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static final String EQUIPPABLE_ASSET_ID = "EQUIPPABLE_ASSET_ID";
    private static final String CMD_RESTORE_ARMOUR = "restore_armour";
    private static final String CMD_HIDE_ARMOUR = "hide_armour";
    private static final String INVISIBLE_LORE_TEXT = "Invisible";
    private static final String ASSET_AIR = "air";
    private static final String ASSET_NETHERITE = "netherite";
    private static final String ERROR_NOT_PLAYER = "You must be a player to use this command";
    private static final String ERROR_NO_ITEM = "You must be holding an item";
    private static final String ERROR_NOT_EQUIPPABLE = "This item is not equippable";
    private static final String ERROR_NO_ASSET_ID = "This item does not have an asset id";
    private static final String MSG_VISIBLE = "Equipment now visible";
    private static final String MSG_INVISIBLE = "Equipment now invisible";

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            registerCommands(dispatcher);
        });
    }

    // Command registration helper
    private static void registerCommands(com.mojang.brigadier.CommandDispatcher<net.minecraft.server.command.ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal(CMD_RESTORE_ARMOUR).executes(PassiveArmour::RestoreArmour));
        dispatcher.register(CommandManager.literal(CMD_HIDE_ARMOUR).executes(PassiveArmour::HideArmour));
    }

    // Validation result record
    private record ValidationResult(ItemStack itemStack, ServerPlayerEntity player, EquippableComponent component,
                                    RegistryKey<EquipmentAsset> assetId, NbtCompound nbtCompound) {}

    // Validation logic
    private static Optional<ValidationResult> validateArmourContext(CommandContext<ServerCommandSource> context) {
        ServerPlayerEntity player = context.getSource().getPlayer();
        if (player == null) {
            sendError(context, ERROR_NOT_PLAYER);
            return Optional.empty();
        }
        ItemStack itemStack = player.getMainHandStack();
        if (itemStack.isEmpty()) {
            sendError(context, ERROR_NO_ITEM);
            return Optional.empty();
        }
        EquippableComponent equippable = itemStack.getOrDefault(DataComponentTypes.EQUIPPABLE, null);
        if (equippable == null) {
            sendError(context, ERROR_NOT_EQUIPPABLE);
            return Optional.empty();
        }
        if (equippable.assetId().isEmpty()) {
            sendError(context, ERROR_NO_ASSET_ID);
            return Optional.empty();
        }
        NbtComponent customData = itemStack.getOrDefault(DataComponentTypes.CUSTOM_DATA, NbtComponent.DEFAULT);
        NbtCompound nbt = customData.copyNbt();
        return Optional.of(new ValidationResult(itemStack, player, equippable, equippable.assetId().get(), nbt));
    }

    // Error/success messaging helpers
    private static void sendError(CommandContext<ServerCommandSource> context, String message) {
        context.getSource().sendError(Text.literal(message));
    }
    private static void sendSuccess(CommandContext<ServerCommandSource> context, String message) {
        context.getSource().sendMessage(Text.literal(message));
    }

    // Netherite item registry keys
    private static List<RegistryKey<?>> getNetheriteItemKeys() {
        return List.of(
            registerVanillaItem("netherite_helmet"),
            registerVanillaItem("netherite_chestplate"),
            registerVanillaItem("netherite_leggings"),
            registerVanillaItem("netherite_boots")
        );
    }
    private static boolean isNetheriteItem(ItemStack itemStack) {
        var itemStackId = itemStack.getRegistryEntry().getKey();
        return itemStackId.isPresent() && getNetheriteItemKeys().contains(itemStackId.get());
    }

    // Command logic
    private static int RestoreArmour(CommandContext<ServerCommandSource> context) {
        Optional<ValidationResult> validationResult = validateArmourContext(context);
        if (validationResult.isEmpty()) return 0;
        ValidationResult result = validationResult.get();
        ItemStack itemStack = result.itemStack;
        EquippableComponent equippable = result.component;
        NbtCompound nbtCompound = result.nbtCompound;
        Optional<RegistryKey<EquipmentAsset>> val = nbtCompound.get(EQUIPPABLE_ASSET_ID, RegistryKey.createCodec(EquipmentAssetKeys.REGISTRY_KEY));
        if (val.isEmpty()) {
            sendError(context, ERROR_NO_ASSET_ID);
            return 0;
        }
        RegistryKey<EquipmentAsset> assetId = val.get();
        if (isNetheriteItem(itemStack)) {
            assetId = registerVanilla(ASSET_NETHERITE);
        }
        itemStack.set(DataComponentTypes.EQUIPPABLE, withAssetId(equippable, assetId));
        nbtCompound.remove(EQUIPPABLE_ASSET_ID);
        itemStack.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(nbtCompound));
        itemStack.apply(DataComponentTypes.LORE, LoreComponent.DEFAULT, loreComponent -> {
            List<Text> loreList = new ArrayList<>(loreComponent.lines());
            loreList.removeIf(text -> text.getString().equals(INVISIBLE_LORE_TEXT));
            return new LoreComponent(loreList);
        });
        sendSuccess(context, MSG_VISIBLE);
        return 1;
    }

    private static int HideArmour(CommandContext<ServerCommandSource> context) {
        return setArmour(context, registerVanilla(ASSET_AIR));
    }

    // For future extensibility (not used currently)
    private static int SetArmourCmdArgument(CommandContext<ServerCommandSource> context) {
        String assetIdStr = context.getArgument("assetId", String.class);
        RegistryKey<EquipmentAsset> assetIdArg = registerVanilla(assetIdStr);
        return setArmour(context, assetIdArg);
    }

    private static int setArmour(CommandContext<ServerCommandSource> context, RegistryKey<EquipmentAsset> assetIdArg) {
        sendSuccess(context, "Asset ID: " + assetIdArg);
        Optional<ValidationResult> validationResult = validateArmourContext(context);
        if (validationResult.isEmpty()) return 0;
        ValidationResult result = validationResult.get();
        ItemStack itemStack = result.itemStack;
        EquippableComponent equippable = result.component;
        NbtCompound nbtCompound = result.nbtCompound;
        Optional<RegistryKey<EquipmentAsset>> val = nbtCompound.get(EQUIPPABLE_ASSET_ID, RegistryKey.createCodec(EquipmentAssetKeys.REGISTRY_KEY));
        if (val.isEmpty()) {
            nbtCompound.put(EQUIPPABLE_ASSET_ID, RegistryKey.createCodec(EquipmentAssetKeys.REGISTRY_KEY), result.assetId);
        }
        itemStack.set(DataComponentTypes.EQUIPPABLE, withAssetId(equippable, assetIdArg));
        itemStack.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(nbtCompound));
        itemStack.apply(DataComponentTypes.LORE, LoreComponent.DEFAULT, loreComponent -> {
            List<Text> loreList = new ArrayList<>(loreComponent.lines());
            loreList.removeIf(text -> text.getString().equals(INVISIBLE_LORE_TEXT));
            loreList.add(Text.literal(INVISIBLE_LORE_TEXT));
            return new LoreComponent(loreList);
        });
        sendSuccess(context, MSG_INVISIBLE);
        return 1;
    }

    // Registry helpers
    private static RegistryKey<EquipmentAsset> registerVanilla(String name) {
        return RegistryKey.of(EquipmentAssetKeys.REGISTRY_KEY, Identifier.ofVanilla(name));
    }
    private static RegistryKey<EquipmentAsset> registerVanillaItem(String name) {
        return RegistryKey.of(RegistryKey.ofRegistry(Identifier.ofVanilla("item")), Identifier.ofVanilla(name));
    }

    // Equippable builder helper
    private static EquippableComponent withAssetId(EquippableComponent component, RegistryKey<EquipmentAsset> assetId) {
        EquippableComponent.Builder builder = EquippableComponent.builder(component.slot());
        builder.model(assetId);
        builder.damageOnHurt(component.damageOnHurt());
        builder.dispensable(component.dispensable());
        builder.equipOnInteract(component.equipOnInteract());
        builder.swappable(component.swappable());
        builder.equipSound(component.equipSound());
        builder.canBeSheared(component.canBeSheared());
        builder.shearingSound(component.shearingSound());
        if (component.allowedEntities().isPresent()) {
            builder.allowedEntities(component.allowedEntities().get());
        }
        if (component.cameraOverlay().isPresent()) {
            builder.cameraOverlay(component.cameraOverlay().get());
        }

        return builder.build();
    }
}