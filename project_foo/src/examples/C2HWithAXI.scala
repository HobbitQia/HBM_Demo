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
        val c2h_ar      = Decoupled(new AXI_ADDR(33, 256, 6, 0, 4))
        val c2h_r       = Flipped(Decoupled(new AXI_DATA_R(33, 256, 6, 0)))
        // val c2h_b       = Flipped(Decoupled(new AXI_BACK(33, 256, 6, 0)))
	})

	val MAX_Q = 32
	
	val q_addr_seq			= RegInit(UInt(64.W),0.U)
	val tags				= RegInit(VecInit(Seq.fill(MAX_Q)(0.U(7.W))))
	
	val burst_words			= 1.U
	val cur_q				= RegInit(UInt(log2Up(MAX_Q).W),0.U)
	val cur_data_q			= RegInit(UInt(log2Up(MAX_Q).W),0.U)
	val valid_cmd			= RegInit(UInt(32.W),0.U)
	val valid_data			= RegInit(UInt(32.W),0.U)
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
	val data_bits		= io.c2h_data.bits
	io.c2h_data.valid 	:= valid_data
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

	val sNone :: sAR :: sR :: sC2HDone :: Nil = Enum(4)//must lower case for first letter!!!
	val AXI_state			= RegInit(sNone)
	val send_c2h_count = RegInit(UInt(32.W), 0.U)
	val tot_c2h_count  = RegInit(UInt(32.W), 0.U)
	val now_addr = RegInit(UInt(33.W), 0.U)
	val c2h_ar_valid	= RegInit(Bool(),false.B)
	val c2h_r_ready		= RegInit(Bool(),false.B)
	val c2h_r_data 		= RegInit(UInt(256.W), 0.U)
	val c2h_r_last 		= RegInit(Bool(), false.B)
	// data_bits.data := c2h_r_data
	// data_bits.last := c2h_r_last
	// io.c2h_r.bits.hbm_init()
	io.c2h_ar.bits.hbm_init()
	io.c2h_ar.valid := c2h_ar_valid
	io.c2h_r.ready := c2h_r_ready
	io.c2h_ar.bits.addr := now_addr
	switch(AXI_state){
		is(sNone){
			tot_c2h_count := 0.U
			now_addr := Cat(io.target_hbm, io.target_addr)
			io.c2h_ar.bits.len  := io.length / 32.U

			when(io.start===1.U){
				AXI_state		:= sAR
			}
		}
		is(sAR){
			send_c2h_count := 0.U
			c2h_ar_valid := true.B
			valid_data := false.B
			when(tot_c2h_count === io.total_words) {
				c2h_ar_valid := false.B
				AXI_state		:= sC2HDone
			}.elsewhen(io.c2h_ar.fire()) {
				AXI_state		:= sR
				c2h_ar_valid := false.B
				c2h_r_ready := true.B
			}
		}
		is(sR){
			when(io.c2h_r.fire()) {
				c2h_r_data := Cat(0.U(256.W), io.c2h_r.bits.data)
				c2h_r_last := io.c2h_r.bits.last
				valid_data := true.B
				c2h_r_ready := false.B
			}
			when(io.c2h_data.fire()) {
				send_c2h_count := send_c2h_count + 1.U
				when(send_c2h_count + 1.U === io.length / 32.U) {		// 一个地址对应 32 个字节
					AXI_state		:= sAR
					tot_c2h_count := tot_c2h_count + 1.U
					valid_data := false.B
					now_addr := now_addr + 32.U
				}
			}
		}
		is(sC2HDone) {
			when(rising_start){
				AXI_state		:= sNone
			}
		}
	}
	data_bits.data := c2h_r_data
	data_bits.last := c2h_r_last

	io.count_cmd			:= count_send_cmd
	io.count_word			:= count_send_word
	io.count_time			:= count_time


}