package com.xy2407.kingdom.client.entity;

import com.xy2407.kingdom.common.entity.NpcEntity;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * 市民模型，坐下时双腿前伸、躯干直立，并同步各附加层。
 */
@OnlyIn(Dist.CLIENT)
public class NpcModel extends PlayerModel<NpcEntity> {

    private static final float SWING_DURATION = 6.0F;

    public NpcModel(ModelPart root, boolean slim) {
        super(root, slim);
    }

    @Override
    public void setupAnim(NpcEntity entity, float limbSwing, float limbSwingAmount, float ageInTicks,
                          float netHeadYaw, float headPitch) {
        this.rightArmPose = HumanoidModel.ArmPose.EMPTY;
        this.leftArmPose = HumanoidModel.ArmPose.EMPTY;
        if (entity.isBlocking()) {
            if (entity.getUsedItemHand() == InteractionHand.OFF_HAND) {
                this.leftArmPose = HumanoidModel.ArmPose.BLOCK;
            } else {
                this.rightArmPose = HumanoidModel.ArmPose.BLOCK;
            }
        }
        super.setupAnim(entity, limbSwing, limbSwingAmount, ageInTicks, netHeadYaw, headPitch);
        applySwing(entity, ageInTicks);
        if (!entity.isSitting()) {
            return;
        }
        this.rightLeg.xRot = -1.5F;
        this.leftLeg.xRot = -1.5F;
        this.rightLeg.yRot = 0.15F;
        this.leftLeg.yRot = -0.15F;
        this.body.xRot = 0.0F;
        this.rightArm.xRot = -0.2F;
        this.leftArm.xRot = -0.2F;
        this.leftPants.copyFrom(this.leftLeg);
        this.rightPants.copyFrom(this.rightLeg);
        this.leftSleeve.copyFrom(this.leftArm);
        this.rightSleeve.copyFrom(this.rightArm);
        this.jacket.copyFrom(this.body);
        this.hat.copyFrom(this.head);
    }

    private void applySwing(NpcEntity entity, float ageInTicks) {
        float progress = (ageInTicks - entity.swingAnimStart()) / SWING_DURATION;
        if (progress < 0.0F || progress >= 1.0F) {
            return;
        }
        float wave = Mth.sin(progress * Mth.PI);
        this.rightArm.xRot = -1.35F * wave;
        this.rightArm.yRot = -0.35F * wave;
        this.rightArm.zRot = 0.0F;
        this.rightSleeve.copyFrom(this.rightArm);
    }
}
