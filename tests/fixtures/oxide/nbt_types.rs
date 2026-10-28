//
// TODO:
// THIS IS JUST A COPY OF lib::types::nbt BECAUSE I COULDNT MOVE IT HERE THANKS TO THE ORPHAN RULE.
// Restructure types to fix
//

#[derive(Debug, Clone, PartialEq, PartialOrd)]
pub enum NbtTag {
	Byte(String, u8),
	Short(String, i16),
	Int(String, i32),
	Long(String, i64),
	Float(String, f32),
	Double(String, f64),
	ByteArray(String, Vec<u8>),
	String(String, String),
	List(String, Vec<NbtListTag>),
	TagCompound(String, Vec<NbtTag>),
	Root(Vec<NbtTag>),
	IntArray(String, Vec<i32>),
	LongArray(String, Vec<i64>),
}

#[derive(Debug, Clone, PartialEq, PartialOrd)]
pub enum NbtListTag {
	Byte(u8),
	Short(i16),
	Int(i32),
	Long(i64),
	Float(f32),
	Double(f64),
	ByteArray(Vec<u8>),
	String(String),
	List(Vec<NbtListTag>),
	TagCompound(Vec<NbtTag>),
	IntArray(Vec<i32>),
	LongArray(Vec<i64>),
}
