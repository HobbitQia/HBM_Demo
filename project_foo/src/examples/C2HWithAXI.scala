package project_foo.examples

import chisel3._
import chisel3.util._
import qdma.examples._
import qdma._
import common.axi._

class C2HWithAXI() extends Module{
	val io = IO(new Bundle{
		val start_addr	= Input(UInt(64.W))
		val length		= Input(UInt(32.W))
		val offset		= Input(UInt(32.W))
		val start		= Input(UInt(32.W))

		val total_words	= Input(UInt(32.W))//total_cmds*length/64
		val total_qs	= Input(UInt(32.W))
		val total_cmds	= Input(UInt(32.W))
		val pfch_tag	= Input(UInt(32.W))
		val tag_index	= Input(UInt(32.W))
        // HBM 用
        val target_hbm  = Input(UInt(1.W))
        val target_addr = Input(UInt(32.W))

		val count_cmd 	= Output(UInt(32.W))
		val count_word 	= Output(UInt(32.W))
		val count_time	= Output(UInt(32.W))
        // val back  = Output(Bool())

		val c2h_cmd		= Decoupled(new C2H_CMD)
		val c2h_data	= Decoupled(new C2H_DATA) 
        val c2h_ar_1      = Decoupled(new AXI_ADDR(33, 256, 6, 0, 4))
        val c2h_r_1       = Flipped(Decoupled(new AXI_DATA_R(33, 256, 6, 0)))

		val c2h_ar_2      = Decoupled(new AXI_ADDR(33, 256, 6, 0, 4))
        val c2h_r_2       = Flipped(Decoupled(new AXI_DATA_R(33, 256, 6, 0)))
	})

	val MAX_Q = 32
	
	val q_addr_seq			= RegInit(UInt(64.W),0.U)
	val tags				= RegInit(VecInit(Seq.fill(MAX_Q)(0.U(7.W))))
	
	val burst_words			= 1.U
	val cur_q				= RegInit(UInt(log2Up(MAX_Q).W),0.U)
	val cur_data_q			= RegInit(UInt(log2Up(MAX_Q).W),0.U)
	val valid_cmd			= RegInit(UInt(32.W),0.U)
	val count_burst_word	= RegInit(UInt(32.W),0.U)
	val count_send_cmd		= RegInit(UInt(32.W),0.U)
	val count_send_word		= RegInit(UInt(32.W),0.U)
	val count_time			= RegInit(UInt(32.W),0.U)
	val rising_start		= io.start===1.U & !RegNext(io.start===1.U)

	//port cmd
	val cmd_bits		= io.c2h_cmd.bits
	io.c2h_cmd.valid 	:= valid_cmd
	cmd_bits			:= 0.U.asTypeOf(new C2H_CMD)
	cmd_bits.qid		:= cur_q
	cmd_bits.addr		:= q_addr_seq

	cmd_bits.pfch_tag	:= tags(cur_q)
	cmd_bits.len		:= io.length

	//port data
	val valid_data_1 = Wire(Bool())
	val valid_data_2 = Wire(Bool())
	io.c2h_data.valid := valid_data_1 & valid_data_2
	val data_bits		= io.c2h_data.bits
	// io.c2h_data.valid 	:= valid_data
	data_bits			:= 0.U.asTypeOf(new C2H_DATA)
	data_bits.ctrl_qid	:= cur_data_q

	when(io.tag_index === (RegNext(io.tag_index)+1.U)){
		tags(RegNext(io.tag_index))	:= io.pfch_tag
	}

	when(io.start === 1.U){
		when(count_send_word =/= io.total_words){
			count_time	:= count_time + 1.U
		}.otherwise{
			count_time	:= count_time
		}
	}.otherwise{
		count_time	:= 0.U
	}

	//state machine
	val cmd_nearly_done = io.c2h_cmd.fire() && (count_send_cmd + 1.U === io.total_cmds)
	val data_nearly_done = io.c2h_data.fire() && (count_send_word + 1.U === io.total_words)

	val sIDLE :: sSEND :: sDONE :: Nil = Enum(3)
	val state_cmd			= RegInit(sIDLE)
	val state_data			= RegInit(sIDLE)
	switch(state_cmd){
		is(sIDLE){
			count_send_cmd			:= 0.U
			count_send_word			:= 0.U
			cur_q					:= 0.U
			cur_data_q				:= 0.U
			count_burst_word		:= 0.U
			q_addr_seq				:= io.start_addr
			when(io.start===1.U){
				state_cmd			:= sSEND
			}
		}
		is(sSEND){
			valid_cmd				:= true.B
			when(cmd_nearly_done){
				state_cmd			:= sDONE
				valid_cmd			:= false.B
			}
		}
		is(sDONE){
			when(rising_start){
				state_cmd		:= sIDLE
			}
		}
	}

	when(io.c2h_cmd.fire()){
		count_send_cmd		:= count_send_cmd + 1.U
		q_addr_seq			:= q_addr_seq + io.length
		
		when(cur_q+1.U === io.total_qs){
			cur_q			:= 0.U
		}.otherwise{
			cur_q			:= cur_q + 1.U
		}
	}

	when(io.c2h_data.fire()){
		count_send_word			:= count_send_word + 1.U

		when(count_burst_word+1.U === burst_words){
			io.c2h_data.bits.last	:= 1.U
			count_burst_word	:= 0.U
			when(cur_data_q+1.U === io.total_qs){
				cur_data_q		:= 0.U
			}.otherwise{
				cur_data_q		:= cur_data_q + 1.U
			}
		}.otherwise{
			count_burst_word	:= count_burst_word + 1.U
		}
	}

	val c2h_1 = Module(new C2HAXIHelper(HIGH_OR_LOW=false))		// 处理低 32 位
	val c2h_2 = Module(new C2HAXIHelper(HIGH_OR_LOW=true))		// 处理高 32 位
	val fire_1 = Wire(Bool())
	val fire_2 = Wire(Bool())
	
	c2h_1.io.start <> io.start
	c2h_1.io.total_words <> io.total_words
	c2h_1.io.target_hbm <> io.target_hbm
	c2h_1.io.target_addr <> io.target_addr
	c2h_1.io.c2h_data_fire := fire_1

	c2h_2.io.start <> io.start
	c2h_2.io.total_words <> io.total_words
	c2h_2.io.target_hbm <> io.target_hbm
	c2h_2.io.target_addr <> io.target_addr
	c2h_2.io.c2h_data_fire := fire_2


	io.c2h_ar_1 <> c2h_1.io.c2h_ar
	c2h_1.io.c2h_r <> io.c2h_r_1
	io.c2h_ar_2 <> c2h_2.io.c2h_ar
	c2h_2.io.c2h_r <> io.c2h_r_2

	// c2h_1.io.len := io.length / 2.U
	// c2h_2.io.len := io.length / 2.U
	c2h_1.io.len := 32.U
	c2h_2.io.len := 32.U
	valid_data_1 := c2h_1.io.valid_data_out
	valid_data_2 := c2h_2.io.valid_data_out

	

	when(io.c2h_data.fire()) {
		io.c2h_data.bits.data := Cat(c2h_2.io.c2h_data_bits, c2h_1.io.c2h_data_bits)
		// io.c2h_data.bits.last := c2h_1.io.c2h_data_last
		fire_1 := true.B
		fire_2 := true.B
	}.otherwise {
		fire_1 := false.B
		fire_2 := false.B
	}
	io.count_cmd			:= count_send_cmd
	io.count_word			:= count_send_word
	io.count_time			:= count_time


}