package dlindustries.vigillant.system.module.modules.crystal;
import dlindustries.vigillant.system.event.events.*;
import dlindustries.vigillant.system.module.Category;
import dlindustries.vigillant.system.module.Module;
import dlindustries.vigillant.system.module.setting.*;
import dlindustries.vigillant.system.system;
import dlindustries.vigillant.system.utils.*;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.SwordItem;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.*;
import org.lwjgl.glfw.GLFW;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

public final class dtapsetup extends Module implements TickListener, ItemUseListener, AttackListener {
    // AI Control Settings
    private final BooleanSetting fullAuto = new BooleanSetting(EncryptedString.of("Full Auto"), true)
            .setDescription(EncryptedString.of("Fully autonomous operation without keybinds"));
    private final NumberSetting targetRange = new NumberSetting(EncryptedString.of("Target Range"), 1, 12, 6, 0.5)
            .setDescription(EncryptedString.of("Maximum distance for target selection"));
    private final NumberSetting predictionStrength = new NumberSetting(EncryptedString.of("Prediction Strength"), 1, 10, 3, 0.5)
            .setDescription(EncryptedString.of("How far ahead to predict movement"));
    private final BooleanSetting breakOwnCrystals = new BooleanSetting(EncryptedString.of("Break Own Crystals"), true)
            .setDescription(EncryptedString.of("Automatically break crystals you placed"));
    
    // Placement Settings
    private final BooleanSetting checkPlace = new BooleanSetting(EncryptedString.of("Check Place"), true)
            .setDescription(EncryptedString.of("Checks if you can place the obsidian on that block"));
    private final NumberSetting switchDelay = new NumberSetting(EncryptedString.of("Switch Delay"), 0, 20, 0, 1);
    private final NumberSetting switchChance = new NumberSetting(EncryptedString.of("Switch Chance"), 0, 100, 100, 1);
    private final NumberSetting placeDelay = new NumberSetting(EncryptedString.of("Place Delay"), 0, 20, 0, 1);
    private final NumberSetting placeChance = new NumberSetting(EncryptedString.of("Place Chance"), 0, 100, 100, 1);
    private final BooleanSetting workWithTotem = new BooleanSetting(EncryptedString.of("Work With Totem"), false);
    private final BooleanSetting workWithCrystal = new BooleanSetting(EncryptedString.of("Work With Crystal"), true);
    private final BooleanSetting swordSwap = new BooleanSetting(EncryptedString.of("Sword Swap"), false);
    
    // Combat Enhancements
    private final BooleanSetting headbob = new BooleanSetting(EncryptedString.of("Headbob"), true)
            .setDescription(EncryptedString.of("Silently update pitch to break crystals above/below"));
    private final BooleanSetting silentRotate = new BooleanSetting(EncryptedString.of("Silent Rotate"), true)
            .setDescription(EncryptedString.of("Rotate instantly while attacking players"));
    private final BooleanSetting targetPrediction = new BooleanSetting(EncryptedString.of("Target Prediction"), true)
            .setDescription(EncryptedString.of("Predict enemy movement for better accuracy"));
    private final NumberSetting aimAssistRange = new NumberSetting(EncryptedString.of("Aim Assist Range"), 1, 10, 5, 0.5)
            .setDescription(EncryptedString.of("Max distance for aim assistance"));
    private final BooleanSetting autoDisable = new BooleanSetting(EncryptedString.of("Auto Disable"), false)
            .setDescription(EncryptedString.of("Disable module when no targets available"));

    // State variables
    private int placeClock = 0;
    private int switchClock = 0;
    private int cooldown = 0;
    private boolean active;
    private boolean crystalling;
    private boolean crystalSelected;
    private float originalPitch;
    private boolean pitchAdjusted;
    private PlayerEntity currentTarget;
    private BlockPos targetObiPos;
    private BlockPos placedCrystalPos;
    private int combatState = 0; // 0=idle, 1=placing obi, 2=placing crystal, 3=breaking

    public dtapsetup() {
        super(EncryptedString.of("AI D-tap Optimizer"),
                EncryptedString.of("Fully automated crystal combat with AI prediction"),
                -1,
                Category.CRYSTAL);
        addSettings(
            fullAuto, targetRange, predictionStrength, breakOwnCrystals,
            checkPlace, switchDelay, switchChance, placeDelay, placeChance, 
            workWithTotem, workWithCrystal, swordSwap,
            headbob, silentRotate, targetPrediction, aimAssistRange, autoDisable
        );
    }

    @Override
    public void onEnable() {
        eventManager.add(TickListener.class, this);
        eventManager.add(ItemUseListener.class, this);
        eventManager.add(AttackListener.class, this);
        reset();
        super.onEnable();
    }

    @Override
    public void onDisable() {
        eventManager.remove(TickListener.class, this);
        eventManager.remove(ItemUseListener.class, this);
        eventManager.remove(AttackListener.class, this);
        resetPitch();
        super.onDisable();
    }

    @Override
    public void onTick() {
        // Save original pitch
        if (!pitchAdjusted) originalPitch = mc.player.getPitch();
        
        // Headbob feature
        if (headbob.getValue()) handleHeadbob();

        // Cooldown management
        if (cooldown > 0) {
            cooldown--;
            return;
        }

        // Full autonomous AI operation
        if (fullAuto.getValue()) {
            if (mc.currentScreen != null) return;
            
            // Target selection phase
            if (currentTarget == null || !currentTarget.isAlive() || mc.player.distanceTo(currentTarget) > targetRange.getValue()) {
                currentTarget = findBestTarget();
                combatState = 0;
                crystalling = false;
                
                if (autoDisable.getValue() && currentTarget == null) {
                    toggle();
                    return;
                }
            }
            
            // AI combat state machine
            switch (combatState) {
                case 0: // IDLE - Find placement position
                    Optional<BlockPos> obiPos = findOptimalObiPosition();
                    if (obiPos.isPresent()) {
                        targetObiPos = obiPos.get();
                        combatState = 1;
                    }
                    break;
                    
                case 1: // Place obsidian
                    if (placeObsidian(targetObiPos)) {
                        combatState = 2;
                        cooldown = 2; // Short cooldown between actions
                    }
                    break;
                    
                case 2: // Place crystal
                    if (placeCrystal(targetObiPos.up())) {
                        placedCrystalPos = targetObiPos.up();
                        combatState = 3;
                        cooldown = 1;
                    }
                    break;
                    
                case 3: // Break crystal
                    if (breakCrystal(placedCrystalPos)) {
                        combatState = 0;
                        cooldown = 3;
                        crystalling = false;
                    }
                    break;
            }
        }
    }

    // AI Targeting System
    private PlayerEntity findBestTarget() {
        return mc.world.getPlayers().stream()
            .filter(player -> player != mc.player)
            .filter(player -> player.isAlive())
            .filter(player -> mc.player.distanceTo(player) <= targetRange.getValue())
            .min(Comparator.comparingDouble(p -> mc.player.distanceTo(p)))
            .orElse(null);
    }

    // Predictive Position Calculation
    private Vec3d predictPosition(Entity entity) {
        return entity.getPos().add(entity.getVelocity().multiply(predictionStrength.getValue()));
    }

    // Find optimal obi placement position
    private Optional<BlockPos> findOptimalObiPosition() {
        if (currentTarget == null) return Optional.empty();
        
        Vec3d predictedPos = predictPosition(currentTarget);
        BlockPos targetPos = new BlockPos((int)predictedPos.x, (int)predictedPos.y, (int)predictedPos.z);
        
        // Check positions around target
        for (BlockPos pos : new BlockPos[]{
            targetPos.down(),
            targetPos.north(),
            targetPos.south(),
            targetPos.east(),
            targetPos.west(),
            targetPos.north().east(),
            targetPos.north().west(),
            targetPos.south().east(),
            targetPos.south().west()
        }) {
            if (BlockUtils.canPlaceBlockClient(pos) && 
                mc.player.squaredDistanceTo(Vec3d.ofCenter(pos)) <= 36) {
                return Optional.of(pos);
            }
        }
        return Optional.empty();
    }

    // Place obsidian with AI
    private boolean placeObsidian(BlockPos pos) {
        if (switchClock > 0) {
            switchClock--;
            return false;
        }

        if (!mc.player.isHolding(Items.OBSIDIAN)) {
            if (MathUtils.randomInt(1, 100) <= switchChance.getValueInt()) {
                switchClock = switchDelay.getValueInt();
                return InventoryUtils.selectItemFromHotbar(Items.OBSIDIAN);
            }
            return false;
        }

        if (placeClock > 0) {
            placeClock--;
            return false;
        }

        if (MathUtils.randomInt(1, 100) <= placeChance.getValueInt()) {
            BlockHitResult hit = new BlockHitResult(
                Vec3d.ofCenter(pos.down()), 
                Direction.UP, 
                pos.down(), 
                false
            );
            
            WorldUtils.placeBlock(hit, true);
            placeClock = placeDelay.getValueInt();
            return true;
        }
        return false;
    }

    // Place crystal with AI
    private boolean placeCrystal(BlockPos pos) {
        if (!mc.player.isHolding(Items.END_CRYSTAL) && !crystalSelected) {
            if (switchClock > 0) {
                switchClock--;
                return false;
            }

            if (MathUtils.randomInt(1, 100) <= switchChance.getValueInt()) {
                crystalSelected = InventoryUtils.selectItemFromHotbar(Items.END_CRYSTAL);
                switchClock = switchDelay.getValueInt();
                return false;
            }
        }

        if (mc.player.isHolding(Items.END_CRYSTAL)) {
            BlockHitResult hit = new BlockHitResult(
                Vec3d.ofCenter(pos), 
                Direction.UP, 
                pos, 
                false
            );
            
            WorldUtils.placeBlock(hit, true);
            return true;
        }
        return false;
    }

    // Break crystal with AI
    private boolean breakCrystal(BlockPos pos) {
        List<EndCrystalEntity> crystals = mc.world.getEntitiesByClass(
            EndCrystalEntity.class,
            new Box(pos),
            crystal -> crystal.isAlive() && (breakOwnCrystals.getValue() || !isOwnCrystal(crystal))
        );

        if (!crystals.isEmpty()) {
            // Silent rotate to crystal
            if (silentRotate.getValue()) rotateToPosition(Vec3d.ofCenter(pos));
            
            // Attack crystal
            mc.interactionManager.attackEntity(mc.player, crystals.get(0));
            mc.player.swingHand(mc.player.getActiveHand());
            return true;
        }
        return false;
    }

    // Headbob implementation
    private void handleHeadbob() {
        Vec3d eyePos = mc.player.getEyePos();
        Box searchBox = new Box(
            eyePos.x - 3, eyePos.y - 3, eyePos.z - 3,
            eyePos.x + 3, eyePos.y + 3, eyePos.z + 3
        );

        List<EndCrystalEntity> crystals = mc.world.getEntitiesByClass(
            EndCrystalEntity.class,
            searchBox,
            crystal -> crystal.isAlive() && !crystal.isRemoved()
        );

        if (!crystals.isEmpty()) {
            crystals.sort(Comparator.comparingDouble(c -> Math.abs(c.getY() - eyePos.y)));
            EndCrystalEntity targetCrystal = crystals.get(0);
            Vec3d crystalPos = targetCrystal.getPos();
            
            double deltaY = crystalPos.y - eyePos.y;
            double horizontalDist = Math.sqrt(
                Math.pow(crystalPos.x - eyePos.x, 2) +
                Math.pow(crystalPos.z - eyePos.z, 2)
            );
            
            float requiredPitch = (float) -Math.toDegrees(Math.atan2(deltaY, horizontalDist));
            mc.player.setPitch(requiredPitch);
            pitchAdjusted = true;
        } else if (pitchAdjusted) {
            resetPitch();
        }
    }

    private void resetPitch() {
        mc.player.setPitch(originalPitch);
        pitchAdjusted = false;
    }

    @Override
    public void onItemUse(ItemUseEvent event) {
        if (fullAuto.getValue()) {
            ItemStack mainHandStack = mc.player.getMainHandStack();
            if (mainHandStack.isOf(Items.END_CRYSTAL) || 
                mainHandStack.isOf(Items.OBSIDIAN)) {
                event.cancel();
            }
        }
    }

    @Override
    public void onAttack(AttackEvent event) {
        if (event.getTarget() instanceof PlayerEntity) {
            // Target prediction and silent rotation
            if (targetPrediction.getValue()) {
                Vec3d predictedPos = predictPosition(event.getTarget());
                rotateToPosition(predictedPos.add(0, event.getTarget().getEyeHeight(event.getTarget().getPose()), 0));
            }
            else if (silentRotate.getValue()) {
                rotateToPosition(event.getTarget().getPos().add(0, event.getTarget().getEyeHeight(event.getTarget().getPose()), 0));
            }
            
            // Cancel if not in combat mode
            if (fullAuto.getValue() && !mc.player.isHolding(Items.END_CRYSTAL)) {
                event.cancel();
            }
        }
    }

    // Advanced rotation system
    private void rotateToPosition(Vec3d targetPos) {
        Vec3d eyePos = mc.player.getEyePos();
        double deltaX = targetPos.x - eyePos.x;
        double deltaY = targetPos.y - eyePos.y;
        double deltaZ = targetPos.z - eyePos.z;
        double horizontalDistance = Math.sqrt(deltaX * deltaX + deltaZ * deltaZ);
        
        float yaw = (float) Math.toDegrees(Math.atan2(deltaZ, deltaX)) - 90f;
        float pitch = (float) -Math.toDegrees(Math.atan2(deltaY, horizontalDistance));
        
        // Instant rotation
        mc.player.setYaw(yaw);
        mc.player.setPitch(pitch);
    }

    // Crystal ownership tracking
    private boolean isOwnCrystal(EndCrystalEntity crystal) {
        // Implement crystal ownership tracking if needed
        return false;
    }

    public void reset() {
        placeClock = placeDelay.getValueInt();
        switchClock = switchDelay.getValueInt();
        cooldown = 0;
        active = false;
        crystalling = false;
        crystalSelected = false;
        currentTarget = null;
        targetObiPos = null;
        placedCrystalPos = null;
        combatState = 0;
        resetPitch();
    }
}
