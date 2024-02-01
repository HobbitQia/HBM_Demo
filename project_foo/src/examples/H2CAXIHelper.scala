package project_foo.examples

import chisel3._
import chisel3.util._
import qdma.examples._
import qdma._
import common.axi._

class H2CAXIHelper(HIGH_OR_LOW: Boolean) extends Module{
    val io = IO(new Bundle{
		val len		= Input(UInt(32.W))
		val start		= Input(UInt(32.W))
		val total_words	= Input(UInt(32.W))//total_cmds*len/64
		// HBM 用
        val target_hbm  = Input(UInt(1.W))
        val target_addr = Input(UInt(32.W))

		val h2c_data_bits	= Input(new H2C_DATA)
		val h2c_data_fire   = Input(Bool())
        val h2c_aw      = Decoupled(new AXI_ADDR(33, 256, 6, 0, 4))
        val h2c_w       = Decoupled(new AXI_DATA_W(33, 256, 6, 0))
		val h2c_b 		= Flipped(Decoupled(new AXI_BACK(33, 256, 6, 0)))

        val h2c_data_ready_out = Output(Bool())
        val h2c_b_out = Output(UInt(32.W))
	})
    val h2c_data_ready = RegInit(Bool(),false.B)
    val data_bits		= io.h2c_data_bits
    val cur_word		= RegInit(UInt(32.W),0.U)
	val rising_start		= io.start===1.U & !RegNext(io.start===1.U)

    val sNone :: sAW :: sW :: sH2CDone :: Nil = Enum(4)//must lower case for first letter!!!
	val AXI_state			= RegInit(sNone)
	val send_h2c_count = RegInit(UInt(32.W), 0.U)
	val tot_h2c_count  = RegInit(UInt(32.W), 0.U)
	val now_addr = RegInit(UInt(33.W), 0.U)
	val h2c_aw_valid	= RegInit(Bool(),false.B)
	val h2c_w_valid		= RegInit(Bool(),false.B)
	val h2c_w_data 		= RegInit(UInt(256.W), 0.U)
	val h2c_w_last 		= RegInit(Bool(), false.B)
	io.h2c_w.bits.hbm_init()
	io.h2c_aw.bits.hbm_init()
	io.h2c_aw.valid := h2c_aw_valid
	io.h2c_w.valid := h2c_w_valid
	io.h2c_aw.bits.addr := now_addr



	switch(AXI_state){
		is(sNone){
			cur_word := 0.U
			tot_h2c_count := 0.U
            if (HIGH_OR_LOW) {
                now_addr := Cat(io.target_hbm, io.target_addr) + 32.U
            }
            else {
                now_addr := Cat(io.target_hbm, io.target_addr) 
            }
			io.h2c_aw.bits.len  := io.len / 32.U
            h2c_data_ready := false.B
			// 不能立刻就开始？
			when(io.start===1.U){
				AXI_state		:= sAW
			}
		}
		is(sAW){
			send_h2c_count := 0.U
			h2c_aw_valid := true.B
			when(tot_h2c_count === io.total_words) {
				h2c_aw_valid := false.B
				AXI_state		:= sH2CDone
			}.elsewhen(io.h2c_aw.fire()) {
				// now_addr := now_addr + 32.U
				AXI_state		:= sW
				h2c_aw_valid := false.B
				h2c_data_ready := true.B
			}
		}
		is(sW){
			when(io.h2c_data_fire) {
                if (HIGH_OR_LOW) {
                    h2c_w_data := data_bits.data(511, 256)
                }
                else {
                    h2c_w_data := data_bits.data(255, 0)
                }
				h2c_w_last := data_bits.last
				h2c_w_valid := true.B
				h2c_data_ready := false.B
			}
			when(io.h2c_w.fire()) {
				send_h2c_count := send_h2c_count + 1.U
				// 这里我们一次传输一个字（256bits）
				when(send_h2c_count + 1.U === io.len / 32.U) {		// 一个地址对应 32 个字节
					h2c_w_valid := false.B
					AXI_state		:= sAW
					tot_h2c_count := tot_h2c_count + 1.U
					now_addr := now_addr + 64.U
				}
			}
		}
		is(sH2CDone) {
			when(rising_start){
				AXI_state		:= sNone
			}
		}
	}
	io.h2c_w.bits.data := h2c_w_data		// 要放在上面逻辑的后面，否则会直接把 h2c_w_bits_data 综合为 0
	io.h2c_w.bits.last := h2c_w_last
	io.h2c_b.ready := true.B
	when(io.h2c_b.fire()){
		cur_word := cur_word + 1.U
	}
    io.h2c_data_ready_out := h2c_data_ready
    io.h2c_b_out := cur_word
}