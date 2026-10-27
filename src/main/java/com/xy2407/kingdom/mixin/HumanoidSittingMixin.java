package com.xy2407.kingdom.mixin;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 坐姿渲染补丁：Pose.SITTING 时双腿前伸、手臂微抬，原版人类模型本身不处理这个姿态
 */
@Mixin(HumanoidModel.class)
public abstract class HumanoidSittingMixin {

    @Shadow
    @Final
    public ModelPart rightLeg;

    @Shadow
    @Final
    public ModelPart leftLeg;

    @Shadow
    @Final
    public ModelPart rightArm;

    @Shadow
    @Final
    public ModelPart leftArm;

    @Inject(method = "setupAnim", at = @At("TAIL"))
    private void kingdomSittingPose(LivingEntity entity, float limbSwing, float limbSwingAmount,
                                    float ageInTicks, float netHeadYaw, float headPitch, CallbackInfo callback) {
        if (entity.getPose() != Pose.SITTING) {
            return;
        }
        this.rightLeg.xRot = -1.5F;
        this.rightLeg.yRot = 0.15F;
        this.leftLeg.xRot = -1.5F;
        this.leftLeg.yRot = -0.15F;
        this.rightArm.xRot = -0.2F;
        this.leftArm.xRot = -0.2F;
    }
}
