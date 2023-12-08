package project_foo.examples

import chisel3._
import chisel3.util._
import qdma.examples._
import qdma._
import common.axi._

class H2CWithAXI() extends Module{
	val io = IO(new Bundle{
		val start_addr	= Input(UInt(64.W))
		val length		= Input(UInt(32.W))
		val offset		= Input(UInt(32.W))
		val sop			= Input(UInt(32.W))
		val eop			= Input(UInt(32.W))
		val start		= Input(UInt(32.W))


		val total_words	= Input(UInt(32.W))//total_cmds*length/64
		val total_qs	= Input(UInt(32.W))
		val total_cmds	= Input(UInt(32.W))
		val range		= Input(UInt(32.W))
		val range_words	= Input(UInt(32.W))
		val is_seq		= Input(UInt(32.W))
		// HBM 用
        val target_hbm  = Input(UInt(1.W))
        val target_addr = Input(UInt(32.W))

		val count_word	= Output(UInt(512.W))
		val count_err	= Output(UInt(32.W))
		val count_time	= Output(UInt(32.W))

		val h2c_cmd		= Decoupled(new H2C_CMD)
		val h2c_data	= Flipped(Decoupled(new H2C_DATA))    
        val h2c_aw_1      = Decoupled(new AXI_ADDR(33, 256, 6, 0, 4))
        val h2c_w_1       = Decoupled(new AXI_DATA_W(33, 256, 6, 0))
		val h2c_b_1 		= Flipped(Decoupled(new AXI_BACK(33, 256, 6, 0)))

		val h2c_aw_2      = Decoupled(new AXI_ADDR(33, 256, 6, 0, 4))
        val h2c_w_2       = Decoupled(new AXI_DATA_W(33, 256, 6, 0))
		val h2c_b_2 		= Flipped(Decoupled(new AXI_BACK(33, 256, 6, 0)))
	})

	val MAX_Q = 32
	// 对于地址和数据，各有一个队列
	val q_addr_seq		= RegInit(UInt(64.W),0.U)
	// q_values 用来检查 QDMA 传过来的值对不对
	// val q_value_seq		= RegInit(UInt(32.W),0.U)
	// val q_values		= RegInit(VecInit(Seq.fill(MAX_Q)(0.U(32.W))))
	// val q_value_max		= VecInit(Seq.fill(MAX_Q)(0.U(32.W)))
	// val q_value_start	= VecInit(Seq.fill(MAX_Q)(0.U(32.W)))
	// 每个 addr 队列，对应的起始地址和结束地址（地址范围），以及当前在操作的地址
	val q_addrs			= RegInit(VecInit(Seq.fill(MAX_Q)(0.U(64.W))))
	val q_addr_start	= RegInit(VecInit(Seq.fill(MAX_Q)(0.U(64.W))))
	val q_addr_end		= RegInit(VecInit(Seq.fill(MAX_Q)(0.U(64.W))))
	val cur_q			= RegInit(UInt(log2Up(MAX_Q).W),0.U)
	val count_err		= RegInit(UInt(32.W),0.U)
	val valid_cmd		= RegInit(Bool(),false.B)

	val send_cmd_count	= RegInit(UInt(32.W),0.U)
	val count_time		= RegInit(UInt(32.W),0.U)
	val cur_word_1		= Wire(UInt(32.W))
	val cur_word_2		= Wire(UInt(32.W))


	val rising_start	= io.start===1.U & !RegNext(io.start===1.U)
	

	//cmd
	val cmd_bits		= io.h2c_cmd.bits
	cmd_bits			:= 0.U.asTypeOf(new H2C_CMD)
	cmd_bits.sop		:= (io.sop===1.U)
	cmd_bits.eop		:= (io.eop===1.U)
	cmd_bits.len		:= io.length
	cmd_bits.qid		:= cur_q
	when(io.is_seq === 1.U){
		cmd_bits.addr		:= q_addr_seq
	}.otherwise{
		cmd_bits.addr		:= q_addrs(cur_q)
	}
	io.h2c_cmd.valid	:= valid_cmd
	

	//data
	val h2c_data_ready_1 = Wire(Bool())
	val h2c_data_ready_2 = Wire(Bool())
	io.h2c_data.ready	:= h2c_data_ready_1 & h2c_data_ready_2

	//state machine
	val sIDLE :: sSEND_CMD :: sDONE :: Nil = Enum(3)//must lower case for first letter!!!
	val state_cmd			= RegInit(sIDLE)

	val cmd_nearly_done = io.h2c_cmd.fire() && (send_cmd_count + 1.U === io.total_cmds)

	// for(i <- 0 until MAX_Q){
	// 	q_value_max(i)			:= io.offset + i.U + io.range_words
	// 	q_value_start(i)		:= io.offset + i.U
	// }

	when(io.start === 1.U){
		when(cur_word_1 =/= io.total_words || cur_word_2 =/= io.total_words){
			count_time	:= count_time + 1.U
		}.otherwise{
			count_time	:= count_time
		}
		
	}.otherwise{
		count_time	:= 0.U
	}

	switch(state_cmd){
		is(sIDLE){
			send_cmd_count		:= 0.U
			valid_cmd			:= false.B
			cur_q				:= 0.U
			q_addr_seq			:= io.start_addr
			// q_value_seq			:= io.offset
			for(i <- 0 until MAX_Q){
				q_addrs(i)		:= io.start_addr + i.U * io.range
				q_addr_start(i)	:= io.start_addr + i.U * io.range
				q_addr_end(i)	:= io.start_addr + (i.U+&1.U) * io.range
				// q_values(i)		:= q_value_start(i)
			}
			when(io.start===1.U){
				state_cmd		:= sSEND_CMD
			}
		}
		is(sSEND_CMD){
			valid_cmd			:= true.B
			when(cmd_nearly_done){
				state_cmd		:= sDONE
				valid_cmd		:= false.B
			}
		}
		is(sDONE){
			when(rising_start){
				state_cmd		:= sIDLE
			}
		}
	}

	when(io.h2c_cmd.fire()){
		send_cmd_count	:= send_cmd_count + 1.U
		q_addr_seq		:= q_addr_seq + io.length

		// for(i <- 0 until MAX_Q){
		// 	when(cur_q === i.U){
		// 		when(q_addrs(i) + io.length === q_addr_end(i)){
		// 			q_addrs(i)	:= q_addr_start(i)
		// 		}.otherwise{
		// 			q_addrs(i)	:= q_addrs(i) + io.length
		// 		}
		// 	}
		// }

		when(cur_q+1.U === io.total_qs){
			cur_q	:= 0.U
		}.otherwise{
			cur_q	:= cur_q + 1.U
		}
	}

	// 实例化
	val h2c_1 = Module(new H2CAXIHelper(HIGH_OR_LOW=false))		// 处理低 32 位
	val h2c_2 = Module(new H2CAXIHelper(HIGH_OR_LOW=true))		// 处理高 32 位
	val fire_1 = Wire(Bool())
	val fire_2 = Wire(Bool())

	h2c_1.io.start <> io.start
	h2c_1.io.total_words <> io.total_words
	h2c_1.io.target_hbm <> io.target_hbm
	h2c_1.io.target_addr <> io.target_addr
	h2c_1.io.h2c_data_fire := fire_1
	h2c_1.io.h2c_data_bits := io.h2c_data.bits
	h2c_data_ready_1 := h2c_1.io.h2c_data_ready_out

	h2c_2.io.start <> io.start
	h2c_2.io.total_words <> io.total_words
	h2c_2.io.target_hbm <> io.target_hbm
	h2c_2.io.target_addr <> io.target_addr
	h2c_2.io.h2c_data_fire := fire_2
	h2c_2.io.h2c_data_bits := io.h2c_data.bits
	h2c_data_ready_2 := h2c_2.io.h2c_data_ready_out
	// 
	io.h2c_aw_1 <> h2c_1.io.h2c_aw
	io.h2c_w_1 <> h2c_1.io.h2c_w
	io.h2c_b_1 <> h2c_1.io.h2c_b

	io.h2c_aw_2 <> h2c_2.io.h2c_aw
	io.h2c_w_2 <> h2c_2.io.h2c_w
	io.h2c_b_2 <> h2c_2.io.h2c_b

	cur_word_1 := h2c_1.io.h2c_b_out
	cur_word_2 := h2c_2.io.h2c_b_out
	// ÷ 2 是因为我们这里有两个 port，一个 port 只需要搬运一半的数据
	// h2c_1.io.len := io.length / 2.U
	// h2c_2.io.len := io.length / 2.U
	h2c_1.io.len := 32.U
	h2c_2.io.len := 32.U

	when(io.h2c_data.fire()) {
		fire_1 := true.B
		fire_2 := true.B
	}.otherwise{
		fire_1 := false.B
		fire_2 := false.B
	}

	io.count_err		:= cur_word_1.asUInt
	io.count_word		:= cur_word_1.asUInt + cur_word_2.asUInt
	io.count_time		:= count_time
}