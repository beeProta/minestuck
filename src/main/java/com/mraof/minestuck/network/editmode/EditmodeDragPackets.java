package com.mraof.minestuck.network.editmode;

import com.mraof.minestuck.Minestuck;
import com.mraof.minestuck.MinestuckConfig;
import com.mraof.minestuck.api.alchemy.GristSet;
import com.mraof.minestuck.api.alchemy.GristTypes;
import com.mraof.minestuck.api.alchemy.MutableGristSet;
import com.mraof.minestuck.api.alchemy.recipe.GristCostRecipe;
import com.mraof.minestuck.computer.editmode.*;
import com.mraof.minestuck.network.MSPacket;
import com.mraof.minestuck.player.GristCache;
import com.mraof.minestuck.util.MSAttachments;
import com.mraof.minestuck.util.MSSoundEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.function.Consumer;

import static com.mraof.minestuck.network.MSPayloads.VEC3_STREAM_CODEC;

public final class EditmodeDragPackets
{
	private static boolean editModePlaceCheck(EditData data, Player player, GristSet cost, BlockPos pos, Consumer<GristSet> missingGristTracker)
	{
		if(!player.level().getBlockState(pos).canBeReplaced())
			return false;
		
		if(!data.getGristCache().canAfford(cost))
		{
			missingGristTracker.accept(cost);
			return false;
		}
		
		return true;
	}
	
	private static boolean editModeDestroyCheck(EditData data, Player player, BlockPos pos, Consumer<GristSet> missingGristTracker)
	{
		BlockState block = player.level().getBlockState(pos);
		ItemStack stack = block.getCloneItemStack(null, player.level(), pos, player);
		DeployEntry entry = DeployList.getEntryForItem(stack, data.sburbData(), player.level(), DeployList.EntryLists.ATHENEUM);
		
		if(block.isAir())
			return false;
		else if(!MinestuckConfig.SERVER.gristRefund.get() && entry == null)
		{
			GristSet cost = GristTypes.BUILD.get().amount(1);
			if(!data.getGristCache().canAfford(cost))
			{
				missingGristTracker.accept(cost);
				return false;
			}
		}
		
		return true;
	}
	
	
	public record Fill(boolean isDown, BlockPos positionStart, BlockPos positionEnd, Vec3 hitVector, Direction side) implements MSPacket.PlayToServer
	{
		public static final Type<Fill> ID = new Type<>(Minestuck.id("editmode_drag/fill"));
		public static final StreamCodec<FriendlyByteBuf, Fill> STREAM_CODEC = StreamCodec.composite(
				ByteBufCodecs.BOOL,
				Fill::isDown,
				BlockPos.STREAM_CODEC,
				Fill::positionStart,
				BlockPos.STREAM_CODEC,
				Fill::positionEnd,
				VEC3_STREAM_CODEC,
				Fill::hitVector,
				Direction.STREAM_CODEC,
				Fill::side,
				Fill::new
		);
		
		@Override
		public Type<? extends CustomPacketPayload> type()
		{
			return ID;
		}
		
		@Override
		public void execute(IPayloadContext context, ServerPlayer player)
		{
			EditData data = ServerEditHandler.getData(player);
			
			if(data == null)
				return;
			
			EditTools cap = player.getData(MSAttachments.EDIT_TOOLS);
			
			cap.setEditPos1(positionStart);
			cap.setEditPos2(positionEnd);
			cap.setEditTrace(hitVector, side);
			
			InteractionHand hand = player.getMainHandItem().isEmpty() ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
			ItemStack stack = player.getItemInHand(hand);
			
			if(stack.isEmpty() || !(stack.getItem() instanceof BlockItem))
				return;
			
			DeployEntry entry = DeployList.getEntryForItem(stack, data.sburbData(), player.level());
			GristSet cost = entry != null ? entry.getCurrentCost(data.sburbData()) : GristCostRecipe.findCostForItem(stack, null, false, player.level());
			
			MutableGristSet missingCost = MutableGristSet.newDefault();
			boolean anyBlockPlaced = false;
			for(BlockPos pos : BlockPos.betweenClosed(positionStart, positionEnd))
			{
				int c = stack.getCount();
				//Will add the block's grist cost to the running tally of how much more grist you need, if you cannot afford it in editModePlaceCheck().
				if(editModePlaceCheck(data, player, cost, pos, missingCost::add) && stack.useOn(new UseOnContext(player, hand, new BlockHitResult(hitVector, side, pos, false))) != InteractionResult.FAIL)
				{
					//Check exists in-case we ever let non-editmode players use this tool for whatever reason.
					if(player.isCreative())
						stack.setCount(c);
					
					//broadcasts the block-place sounds to other players.
					SoundType soundType = ((BlockItem) stack.getItem()).getBlock().defaultBlockState().getSoundType();
					player.level().playSound(player, pos, soundType.getPlaceSound(), SoundSource.BLOCKS, (soundType.getVolume() + 1.0F) / 2.0F, soundType.getPitch() * 0.8F);
					
					anyBlockPlaced = true;
				}
			}
			
			if(anyBlockPlaced)
			{
				//broadcasts edit sound to other players.
				player.level().playSound(player, positionEnd, MSSoundEvents.EVENT_EDIT_TOOL_REVISE.get(), SoundSource.AMBIENT, 1.0f, 1.0f);
				player.swing(hand);
			}
			
			if(!missingCost.isEmpty())
				player.sendSystemMessage(GristCache.createMissingMessage(missingCost), true);
			
			ServerEditHandler.removeCursorEntity(player, !anyBlockPlaced);
		}
	}
	
	public record Destroy(boolean isDown, BlockPos positionStart, BlockPos positionEnd, Vec3 hitVector, Direction side) implements MSPacket.PlayToServer
	{
		public static final Type<Destroy> ID = new Type<>(Minestuck.id("editmode_drag/destroy"));
		public static final StreamCodec<FriendlyByteBuf, Destroy> STREAM_CODEC = StreamCodec.composite(
				ByteBufCodecs.BOOL,
				Destroy::isDown,
				BlockPos.STREAM_CODEC,
				Destroy::positionStart,
				BlockPos.STREAM_CODEC,
				Destroy::positionEnd,
				VEC3_STREAM_CODEC,
				Destroy::hitVector,
				Direction.STREAM_CODEC,
				Destroy::side,
				Destroy::new
		);
		
		@Override
		public Type<? extends CustomPacketPayload> type()
		{
			return ID;
		}
		
		@Override
		public void execute(IPayloadContext context, ServerPlayer player)
		{
			EditData data = ServerEditHandler.getData(player);
			
			if(data == null)
				return;
			
			EditTools cap = player.getData(MSAttachments.EDIT_TOOLS);
			
			cap.setEditPos1(positionStart);
			cap.setEditPos2(positionEnd);
			cap.setEditTrace(hitVector, side);
			
			MutableGristSet missingCost = MutableGristSet.newDefault();
			boolean anyBlockDestroyed = false;
			for(BlockPos pos : BlockPos.betweenClosed(positionStart, positionEnd))
			{
				BlockState block = player.level().getBlockState(pos);
				
				Consumer<GristSet> missingCostTracker = missingCost::add; //Will add the block's grist cost to the running tally of how much more grist you need, if you cannot afford it in editModeDestroyCheck().
				if(editModeDestroyCheck(data, player, pos, missingCostTracker))
				{
					player.gameMode.destroyAndAck(pos, 3, "creative destroy");
					
					//broadcasts block-break particles and sounds to other players.
					player.level().levelEvent(LevelEvent.PARTICLES_DESTROY_BLOCK, pos, Block.getId(block));
					player.level().gameEvent(GameEvent.BLOCK_DESTROY, pos, GameEvent.Context.of(player, block));
					
					anyBlockDestroyed = true;
				}
			}
			
			if(anyBlockDestroyed)
			{
				//broadcasts edit sound to other players.
				player.level().playSound(player, positionEnd, MSSoundEvents.EVENT_EDIT_TOOL_RECYCLE.get(), SoundSource.AMBIENT, 1.0f, 0.85f);
				player.swing(InteractionHand.MAIN_HAND);
			}
			
			if(!missingCost.isEmpty())
				player.sendSystemMessage(GristCache.createMissingMessage(missingCost), true);
			
			ServerEditHandler.removeCursorEntity(player, !anyBlockDestroyed);
		}
	}
	
	public record Cursor(boolean isDown, BlockPos positionStart, BlockPos positionEnd) implements MSPacket.PlayToServer
	{
		public static final Type<Cursor> ID = new Type<>(Minestuck.id("editmode_drag/cursor"));
		public static final StreamCodec<FriendlyByteBuf, Cursor> STREAM_CODEC = StreamCodec.composite(
				ByteBufCodecs.BOOL,
				Cursor::isDown,
				BlockPos.STREAM_CODEC,
				Cursor::positionStart,
				BlockPos.STREAM_CODEC,
				Cursor::positionEnd,
				Cursor::new
		);
		
		@Override
		public Type<? extends CustomPacketPayload> type()
		{
			return ID;
		}
		
		@Override
		public void execute(IPayloadContext context, ServerPlayer player)
		{
			if(ServerEditHandler.isInEditmode(player))
			{
				EditTools cap = player.getData(MSAttachments.EDIT_TOOLS);
				
				cap.setEditPos1(positionStart);
				cap.setEditPos2(positionEnd);
				
				ServerEditHandler.updateEditToolsServer(player, isDown, positionStart, positionEnd);
			}
		}
	}
	
	public record Reset() implements MSPacket.PlayToServer
	{
		public static final Type<Reset> ID = new Type<>(Minestuck.id("editmode_drag/reset"));
		public static final StreamCodec<FriendlyByteBuf, Reset> STREAM_CODEC = StreamCodec.unit(new Reset());
		
		@Override
		public Type<? extends CustomPacketPayload> type()
		{
			return ID;
		}
		
		@Override
		public void execute(IPayloadContext context, ServerPlayer player)
		{
			if(!player.level().isClientSide())
			{
				EditTools cap = player.getData(MSAttachments.EDIT_TOOLS);
				
				ServerEditHandler.removeCursorEntity(player, true);
				cap.resetDragTools();
			}
		}
	}
}
