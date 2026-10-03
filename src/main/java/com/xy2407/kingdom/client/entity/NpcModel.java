package com.xy2407.kingdom.client.entity;

import com.xy2407.kingdom.common.entity.NpcEntity;

import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;

/**
 * 市民模型，坐下时双腿前伸、躯干直立
 */
public class NpcModel extends PlayerModel<NpcEntity> {

    public NpcModel(ModelPart root, boolean slim) {
        super(root, slim);
    }

    @Override
    public void setupAnim(NpcEntity entity, float limbSwing, float limbSwingAmount, float ageInTicks,
                          float netHeadYaw, float headPitch) {
        super.setupAnim(entity, limbSwing, limbSwingAmount, ageInTicks, netHeadYaw, headPitch);
        if (!entity.isSitting()) {
            return;
        }
        // 双腿向前水平伸出，躯干保持垂直
        this.rightLeg.xRot = -1.5F;
        this.leftLeg.xRot = -1.5F;
        this.rightLeg.yRot = 0.15F;
        this.leftLeg.yRot = -0.15F;
        this.body.xRot = 0.0F;
        this.rightArm.xRot = -0.2F;
        this.leftArm.xRot = -0.2F;
        // 附加层（袖子 / 裤腿 / 外套 / 帽子）在 super.setupAnim 末尾已经 copyFrom 过一次，
        // 那次拿到的是站立姿态。这里改完本体必须重新同步一遍，否则附加层会留在原地浮空。
        this.leftPants.copyFrom(this.leftLeg);
        this.rightPants.copyFrom(this.rightLeg);
        this.leftSleeve.copyFrom(this.leftArm);
        this.rightSleeve.copyFrom(this.rightArm);
        this.jacket.copyFrom(this.body);
        this.hat.copyFrom(this.head);
    }
}
