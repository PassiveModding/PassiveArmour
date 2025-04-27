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
    public static final String MOD_ID = "passivearmour";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static final String EQUIPPABLE_ASSET_ID = "EQUIPPABLE_ASSET_ID";

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(CommandManager
                    .literal("restore_armour")
                    .executes(context -> RestoreArmour(context)));
            dispatcher.register(CommandManager
                    .literal("hide_armour")
                    .executes(context -> HideArmour(context)));
        });
    }

    private record ValidationResult(ItemStack itemStack, ServerPlayerEntity player, EquippableComponent component,
                                    RegistryKey<EquipmentAsset> assetId, NbtCompound nbtCompound) {
    }

    private static Optional<ValidationResult> ValidateArmourCtx(CommandContext<ServerCommandSource> context) {
        // Get the player who executed the command
        ServerPlayerEntity player = context.getSource().getPlayer();
        if (player == null) {
            context.getSource().sendError(Text.literal("You must be a player to use this command"));
            return Optional.empty();
        }

        // Get the item in the player's main hand
        ItemStack itemStack = player.getMainHandStack();
        if (itemStack.isEmpty()) {
            context.getSource().sendError(Text.literal("You must be holding an item"));
            return Optional.empty();
        }

        // Check if the item stack has the equippable component
        EquippableComponent equippable = itemStack.getOrDefault(DataComponentTypes.EQUIPPABLE, null);
        if (equippable == null) {
            context.getSource().sendError(Text.literal("This item is not equippable"));
            return Optional.empty();
        }

        if (equippable.assetId().isEmpty()) {
            context.getSource().sendError(Text.literal("This item does not have an asset id"));
            return Optional.empty();
        }

        NbtComponent customData = itemStack.getOrDefault(DataComponentTypes.CUSTOM_DATA, NbtComponent.DEFAULT);
        NbtCompound nbt = customData.copyNbt();

        return Optional.of(new ValidationResult(itemStack, player, equippable, equippable.assetId().get(), nbt));
    }

    private static int RestoreArmour(CommandContext<ServerCommandSource> context) {
        Optional<ValidationResult> validationResult = ValidateArmourCtx(context);
        if (validationResult.isEmpty()) {
            return 0;
        }

        ValidationResult result = validationResult.get();
        ItemStack itemStack = result.itemStack;
        ServerPlayerEntity player = result.player;
        EquippableComponent equippable = result.component;
        NbtCompound nbtCompound = result.nbtCompound;

        Optional<RegistryKey<EquipmentAsset>> val = nbtCompound.get(EQUIPPABLE_ASSET_ID, RegistryKey.createCodec(EquipmentAssetKeys.REGISTRY_KEY));
        if (val.isEmpty()) {
            context.getSource().sendError(Text.literal("This item does not have an asset id"));
            return 0;
        }

        // Get the existing asset id and set it back to the original
        RegistryKey<EquipmentAsset> assetId = val.get();
        itemStack.set(DataComponentTypes.EQUIPPABLE, withAssetId(equippable, assetId));
        nbtCompound.remove(EQUIPPABLE_ASSET_ID);
        itemStack.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(nbtCompound));

        itemStack.apply(DataComponentTypes.LORE, LoreComponent.DEFAULT, loreComponent -> {
            List<Text> loreList = new ArrayList<>(loreComponent.lines());
            loreList.removeIf(text -> text.getString().equals("Invisible"));
            return new LoreComponent(loreList);
        });

        context.getSource().sendMessage(Text.literal("Equipment now visible"));

        return 1;
    }

    private static int HideArmour(CommandContext<ServerCommandSource> context) {
        return SetArmour(context, registerVanilla("air"));
    }

    private static int SetArmourCmdArgument(CommandContext<ServerCommandSource> context) {
        String assetIdStr = context.getArgument("assetId", String.class);
        RegistryKey<EquipmentAsset> assetIdArg = registerVanilla(assetIdStr);
        return SetArmour(context, assetIdArg);
    }

    private static int SetArmour(CommandContext<ServerCommandSource> context, RegistryKey<EquipmentAsset> assetIdArg) {
        context.getSource().sendMessage(Text.literal("Asset ID: " + assetIdArg));

        Optional<ValidationResult> validationResult = ValidateArmourCtx(context);
        if (validationResult.isEmpty()) {
            return 0;
        }

        ValidationResult result = validationResult.get();
        ItemStack itemStack = result.itemStack;
        ServerPlayerEntity player = result.player;
        EquippableComponent equippable = result.component;
        NbtCompound nbtCompound = result.nbtCompound;

        Optional<RegistryKey<EquipmentAsset>> val = nbtCompound.get(EQUIPPABLE_ASSET_ID, RegistryKey.createCodec(EquipmentAssetKeys.REGISTRY_KEY));
        if (val.isEmpty()) {
            nbtCompound.put(EQUIPPABLE_ASSET_ID, RegistryKey.createCodec(EquipmentAssetKeys.REGISTRY_KEY), result.assetId);
        }

        // Set the asset id to the new value
        itemStack.set(DataComponentTypes.EQUIPPABLE, withAssetId(equippable, assetIdArg));
        itemStack.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(nbtCompound));
        itemStack.apply(DataComponentTypes.LORE, LoreComponent.DEFAULT, loreComponent -> {
            List<Text> loreList = new ArrayList<>(loreComponent.lines());
            loreList.removeIf(text -> text.getString().equals("Invisible"));
            loreList.add(Text.literal("Invisible"));
            return new LoreComponent(loreList);
        });

        context.getSource().sendMessage(Text.literal("Equipment now invisible"));
        return 1;
    }

	private static RegistryKey<EquipmentAsset> registerVanilla(String name) {
		return RegistryKey.of(EquipmentAssetKeys.REGISTRY_KEY, Identifier.ofVanilla(name));
	}

    private static EquippableComponent withAssetId(EquippableComponent component, RegistryKey<EquipmentAsset> assetId) {
        EquippableComponent.Builder builder = EquippableComponent.builder(component.slot());
        builder.model(assetId);
        builder.damageOnHurt(component.damageOnHurt());
        builder.dispensable(component.dispensable());
        builder.equipOnInteract(component.equipOnInteract());
        builder.swappable(component.swappable());
        builder.equipSound(component.equipSound());
        if (component.allowedEntities().isPresent()) {
            builder.allowedEntities(component.allowedEntities().get());
        }

        if (component.cameraOverlay().isPresent()) {
            builder.cameraOverlay(component.cameraOverlay().get());
        }

        return builder.build();
    }
}