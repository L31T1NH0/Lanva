use basic_types::blocks::*;
pub fn get_block_state_from_block_state_id(block_state_id: u16) -> State {
	return match block_state_id {
		0 => State {id: 0, properties: vec![]},
		255 => State {id: 255, properties: vec![Property::TintedParticleLeavesDistance(TintedParticleLeavesDistance::Num1),Property::TintedParticleLeavesPersistent(TintedParticleLeavesPersistent::False),Property::TintedParticleLeavesWaterlogged(TintedParticleLeavesWaterlogged::False),]},
		256 => State {id: 256, properties: vec![Property::TintedParticleLeavesDistance(TintedParticleLeavesDistance::Num2),Property::TintedParticleLeavesPersistent(TintedParticleLeavesPersistent::True),Property::TintedParticleLeavesWaterlogged(TintedParticleLeavesWaterlogged::True),]},
		_ => panic!("block_state_id {} doesnt exist", block_state_id)
	}
}
