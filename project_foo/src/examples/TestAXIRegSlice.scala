package project_foo.examples

import chisel3._
import chisel3.util._
import qdma._
import hbm._
import common._
import common.storage._
import common.axi._
import qdma.examples._
import common.partialReconfig.AlveoStaticIO
import project_foo._

class TestAXIRegSlice() extends Module{
    val io = IO(new Bundle{
        val in_aw_addr = Flipped(Decoupled(new AXI_ADDR(33, 256, 6, 0, 4)))
        val in_w_data = Flipped(Decoupled(new AXI_DATA_W(33, 256, 6, 0)))
        val in_ar_addr = Flipped(Decoupled(new AXI_ADDR(33, 256, 6, 0, 4)))
        val in_r_data = Decoupled(new AXI_DATA_R(33, 256, 6, 0))
        val out_aw_addr = Decoupled(new AXI_ADDR(33, 256, 6, 0, 4))
        val out_w_data = Decoupled(new AXI_DATA_W(33, 256, 6, 0))
        val out_ar_addr = Decoupled(new AXI_ADDR(33, 256, 6, 0, 4))
        val out_r_data = Flipped(Decoupled(new AXI_DATA_R(33, 256, 6, 0)))
    })

    val in_axi = Wire(new AXI(33, 256, 6, 0, 4))
	ToZero(in_axi)
	val in_axi_regslice = AXIRegSlice(in_axi) 

    in_axi.aw <> io.in_aw_addr
    in_axi.w <> io.in_w_data
    in_axi.ar <> io.in_ar_addr
    io.in_r_data <> in_axi.r
    in_axi_regslice.b := DontCare

    in_axi_regslice.aw <> io.out_aw_addr
    in_axi_regslice.w <> io.out_w_data
    in_axi_regslice.ar <> io.out_ar_addr
    io.out_r_data <> in_axi_regslice.r

}