package com.xy2407.kingdom.client.entity;

import com.xy2407.kingdom.common.entity.NpcEntity;

import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;

/**
 * 市民模型，坐下时双腿前伸、躯干直立，并同步各附加层。
 */
public class NpcModel extends PlayerModel<NpcEntity> {

    private boolean skipAnim;

    public NpcModel(ModelPart root, boolean slim) {
        super(root, slim);
    }

    public void setSkipAnim(boolean skip) {
        this.skipAnim = skip;
    }

    @Override
    public void setupAnim(NpcEntity entity, float limbSwing, float limbSwingAmount, float ageInTicks,
                          float netHeadYaw, float headPitch) {
        if (skipAnim) {
            return;
        }
        super.setupAnim(entity, limbSwing, limbSwingAmount, ageInTicks, netHeadYaw, headPitch);
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
}
