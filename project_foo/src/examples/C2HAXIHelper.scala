package project_foo.examples

import chisel3._
import chisel3.util._
import qdma.examples._
import qdma._
import common.axi._

class C2HAXIHelper(HIGH_OR_LOW: Boolean) extends Module {
    val io = IO(new Bundle{
		val len		=   Input(UInt(32.W))
		val start		= Input(UInt(32.W))

		val total_words	= Input(UInt(32.W))
        // HBM 用
        val target_hbm  = Input(UInt(1.W))
        val target_addr = Input(UInt(32.W))

		val c2h_data_bits	= Output(UInt(256.W))
        val c2h_data_last   = Output(Bool())
        val c2h_ar      = Decoupled(new AXI_ADDR(33, 256, 6, 0, 4))
        val c2h_r       = Flipped(Decoupled(new AXI_DATA_R(33, 256, 6, 0)))

        val valid_data_out = Output(Bool())
        val c2h_data_fire = Input(Bool())
	})

    val valid_data			= RegInit(Bool(),false.B)
    val rising_start		= io.start===1.U & !RegNext(io.start===1.U)

    val sNone :: sAR :: sR :: sC2HDone :: Nil = Enum(4)//must lower case for first letter!!!
	val AXI_state			= RegInit(sNone)
	val send_c2h_count = RegInit(UInt(32.W), 0.U)
	val tot_c2h_count  = RegInit(UInt(32.W), 0.U)
	val now_addr = RegInit(UInt(33.W), 0.U)
	val c2h_ar_valid	= RegInit(Bool(),false.B)
	val c2h_r_ready		= RegInit(Bool(),false.B)
	val c2h_r_data 		= RegInit(UInt(256.W), 0.U)
	val c2h_r_last 		= RegInit(Bool(), false.B)
	io.c2h_ar.bits.hbm_init()
	io.c2h_ar.valid := c2h_ar_valid
	io.c2h_r.ready := c2h_r_ready
	io.c2h_ar.bits.addr := now_addr
	switch(AXI_state){
		is(sNone){
			tot_c2h_count := 0.U
            if (HIGH_OR_LOW) {
                now_addr := Cat(io.target_hbm, io.target_addr) + 32.U
            }
            else {
                now_addr := Cat(io.target_hbm, io.target_addr) 
            }
			io.c2h_ar.bits.len  := io.len / 32.U

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
			}.elsewhen(io.c2h_ar.fire) {
				AXI_state		:= sR
				c2h_ar_valid := false.B
				c2h_r_ready := true.B
			}
		}
		is(sR){
			when(io.c2h_r.fire) {
				c2h_r_data := io.c2h_r.bits.data
				c2h_r_last := io.c2h_r.bits.last
				valid_data := true.B
				c2h_r_ready := false.B
			}
			when(io.c2h_data_fire) {
				send_c2h_count := send_c2h_count + 1.U
				when(send_c2h_count + 1.U === io.len / 32.U) {		// 一个地址对应 32 个字节
					AXI_state		:= sAR
					tot_c2h_count := tot_c2h_count + 1.U
					valid_data := false.B
					now_addr := now_addr + 64.U
				}
			}
		}
		is(sC2HDone) {
			when(rising_start){
				AXI_state		:= sNone
			}
		}
	}
    io.c2h_data_bits := c2h_r_data
    io.c2h_data_last := c2h_r_last

    io.valid_data_out := valid_data
}