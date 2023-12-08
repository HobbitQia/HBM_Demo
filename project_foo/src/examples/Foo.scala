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

/*
TODO:
1. 重构 H2C C2H（reg_control, reg_status），删掉不用的逻辑
2. H2C C2H 添加地址端口，区分开 hbm 地址端口和 AXI 地址端口
3. 支持多个 AXI 同时读写
4. HBM 端口范围，调整
*/

class Foo extends MultiIOModule{
	override val desiredName = "AlveoDynamicTop"
    val io = IO(Flipped(new AlveoStaticIO(
        VIVADO_VERSION = "202101", 
		QDMA_PCIE_WIDTH = 16, 
		QDMA_SLAVE_BRIDGE = false, 
		QDMA_AXI_BRIDGE = true,
		ENABLE_CMAC_1 = true
    )))

	val dbgBridgeInst = DebugBridge(IP_CORE_NAME="DebugBridgeTest", clk=clock)
	dbgBridgeInst.getTCL()

	dontTouch(io)
    io.cmacPin.get <> DontCare

	val userClk  	= Wire(Clock())
	val userRstn 	= Wire(Bool())
    
	userClk		:= clock		// 这个就是 MMC 分频之后的
	userRstn	:= ~reset.asBool

	
	// 实例化 HBM 模块
	
	val hbm_driver = withClockAndReset(io.sysClk, false.B) {Module(new HBM_DRIVER(WITH_RAMA=false, IP_CORE_NAME="HBMBlackBox"))}
	hbm_driver.getTCL()
	for (i <- 0 until 32) {
		hbm_driver.io.axi_hbm(i).hbm_init()	// Read hbm_init function if you're not familiar with AXI.
	}
	val hbm_clk = Wire(Clock())
	val hbm_rstn = Wire(Bool())
	hbm_clk := hbm_driver.io.hbm_clk
	hbm_rstn := hbm_driver.io.hbm_rstn
	dontTouch(hbm_clk)
	dontTouch(hbm_rstn)

	// 关键是连接 hbm.io.axi_hbm

	// 实例化 QDMA 模块
	val qdma = Module(new QDMADynamic(
		VIVADO_VERSION		= "202101",
		PCIE_WIDTH			= 16,
		SLAVE_BRIDGE		= false,
		BRIDGE_BAR_SCALE	= "Megabytes",
		BRIDGE_BAR_SIZE 	= 4
	))

	ToZero(qdma.io.reg_status)

	qdma.io.qdma_port	<> io.qdma
	qdma.io.user_clk	:= userClk
	qdma.io.user_arstn	:= ((~reset.asBool & ~qdma.io.reg_control(0)(0)).asClock).asBool

	qdma.io.h2c_data.ready	:= 0.U
	qdma.io.c2h_data.valid	:= 0.U
	qdma.io.c2h_data.bits	:= 0.U.asTypeOf(new C2H_DATA)

	qdma.io.h2c_cmd.valid	:= 0.U
	qdma.io.h2c_cmd.bits	:= 0.U.asTypeOf(new H2C_CMD)
	qdma.io.c2h_cmd.valid	:= 0.U
	qdma.io.c2h_cmd.bits	:= 0.U.asTypeOf(new C2H_CMD)
	
	qdma.io.axib := DontCare

	val reg_control = qdma.io.reg_control
	val reg_status = qdma.io.reg_status
	val qdma_h2c_cmd = qdma.io.h2c_cmd
	val qdma_h2c_data = qdma.io.h2c_data
	val qdma_c2h_cmd = qdma.io.c2h_cmd
	val qdma_c2h_data = qdma.io.c2h_data

	val h2c = withClockAndReset(userClk, !qdma.io.user_arstn){ Module(new H2CWithAXI()) }
	h2c.io.h2c_cmd		<> qdma_h2c_cmd
	h2c.io.h2c_data		<> qdma_h2c_data

	val c2h = withClockAndReset(userClk, !qdma.io.user_arstn){ Module(new C2HWithAXI()) }
	c2h.io.c2h_cmd		<> qdma_c2h_cmd
	c2h.io.c2h_data		<> qdma_c2h_data

	// 还需要一个 FIFO
	// target_hbm
	// val channel_1 = 0
	// val channel_2 = 1
	// val base = reg_control(113) * 16.U
	// val channel = reg_control(114)(31,28)
	// val offset = if(channel % 4.U == 3.U(4.W)) { channel - 1.U } else { channel }

	val in_axi_1 = Wire(new AXI(33, 256, 6, 0, 4))
	ToZero(in_axi_1)
	val in_axi_regslice_1 = withClockAndReset(userClk, !qdma.io.user_arstn) { AXIRegSlice(in_axi_1) }
	in_axi_1.aw <> h2c.io.h2c_aw_1
	in_axi_1.w <> h2c.io.h2c_w_1
	in_axi_1.ar <> c2h.io.c2h_ar_1
	c2h.io.c2h_r_1 <> in_axi_1.r
	h2c.io.h2c_b_1 <> in_axi_1.b

	val out_axi_1 = XAXIConverter(in_axi_regslice_1, userClk, qdma.io.user_arstn, hbm_clk, hbm_rstn)
	val out_axi_regslice_1 = withClockAndReset(hbm_clk, !hbm_rstn) { AXIRegSlice(out_axi_1) }
	val hbm_h2c_cmd_1 = out_axi_regslice_1.aw
	val hbm_h2c_data_1 = out_axi_regslice_1.w
	val hbm_c2h_cmd_1 = out_axi_regslice_1.ar
	val hbm_c2h_data_1 = Wire(Decoupled(new AXI_DATA_R(33, 256, 6, 0)))
	ToZero(hbm_c2h_data_1)
	val hbm_h2c_back_1 = Wire(Decoupled(new AXI_BACK(33, 256, 6, 0)))
	out_axi_regslice_1.r.bits := hbm_c2h_data_1.bits
	out_axi_regslice_1.r.valid := hbm_c2h_data_1.valid
	hbm_c2h_data_1.ready := out_axi_regslice_1.r.ready

	out_axi_regslice_1.b.bits := hbm_h2c_back_1.bits
	out_axi_regslice_1.b.valid := hbm_h2c_back_1.valid
	hbm_h2c_back_1.ready := out_axi_regslice_1.b.ready
	
	// val axi_port_1 = hbm_driver.io.axi_hbm(base + offset)
	val axi_port_1 = hbm_driver.io.axi_hbm(0.U)
	hbm_h2c_cmd_1.ready := axi_port_1.aw.ready
	axi_port_1.aw.valid := hbm_h2c_cmd_1.valid
	axi_port_1.aw.bits.addr := hbm_h2c_cmd_1.bits.addr
	axi_port_1.aw.bits.len := hbm_h2c_cmd_1.bits.len
	// size?
	hbm_h2c_data_1.ready := axi_port_1.w.ready
	axi_port_1.w.valid := hbm_h2c_data_1.valid
	axi_port_1.w.bits.data := hbm_h2c_data_1.bits.data
	axi_port_1.w.bits.last := hbm_h2c_data_1.bits.last
	//back
	axi_port_1.b.ready := hbm_h2c_back_1.ready
	hbm_h2c_back_1.valid := axi_port_1.b.valid
	hbm_h2c_back_1.bits <> axi_port_1.b.bits

	// C2H 从 hbm 里读数据（应该是 ar
	hbm_c2h_cmd_1.ready := axi_port_1.ar.ready
	axi_port_1.ar.valid := hbm_c2h_cmd_1.valid
	axi_port_1.ar.bits.addr := hbm_c2h_cmd_1.bits.addr
	axi_port_1.ar.bits.len := hbm_c2h_cmd_1.bits.len

	axi_port_1.r.ready := hbm_c2h_data_1.ready
	hbm_c2h_data_1.valid := axi_port_1.r.valid
	hbm_c2h_data_1.bits.data := axi_port_1.r.bits.data
	hbm_c2h_data_1.bits.last := axi_port_1.r.bits.last
	/*********************************************************************/
	val in_axi_2 = Wire(new AXI(33, 256, 6, 0, 4))
	ToZero(in_axi_2)
	val in_axi_regslice_2 = withClockAndReset(userClk, !qdma.io.user_arstn) { AXIRegSlice(in_axi_2) }
	in_axi_2.aw <> h2c.io.h2c_aw_2
	in_axi_2.w <> h2c.io.h2c_w_2
	in_axi_2.ar <> c2h.io.c2h_ar_2
	c2h.io.c2h_r_2 <> in_axi_2.r
	h2c.io.h2c_b_2 <> in_axi_2.b

	val out_axi_2 = XAXIConverter(in_axi_regslice_2, userClk, qdma.io.user_arstn, hbm_clk, hbm_rstn)
	val out_axi_regslice_2 = withClockAndReset(hbm_clk, !hbm_rstn) { AXIRegSlice(out_axi_2) }
	val hbm_h2c_cmd_2 = out_axi_regslice_2.aw
	val hbm_h2c_data_2 = out_axi_regslice_2.w
	val hbm_c2h_cmd_2 = out_axi_regslice_2.ar
	val hbm_c2h_data_2 = Wire(Decoupled(new AXI_DATA_R(33, 256, 6, 0)))
	ToZero(hbm_c2h_data_2)
	val hbm_h2c_back_2 = Wire(Decoupled(new AXI_BACK(33, 256, 6, 0)))
	out_axi_regslice_2.r.bits := hbm_c2h_data_2.bits
	out_axi_regslice_2.r.valid := hbm_c2h_data_2.valid
	hbm_c2h_data_2.ready := out_axi_regslice_2.r.ready

	out_axi_regslice_2.b.bits := hbm_h2c_back_2.bits
	out_axi_regslice_2.b.valid := hbm_h2c_back_2.valid
	hbm_h2c_back_2.ready := out_axi_regslice_2.b.ready
	
	// val axi_port_2 = hbm_driver.io.axi_hbm(base + offset + 1.U)
	val axi_port_2 = hbm_driver.io.axi_hbm(1.U)
	hbm_h2c_cmd_2.ready := axi_port_2.aw.ready
	axi_port_2.aw.valid := hbm_h2c_cmd_2.valid
	axi_port_2.aw.bits.addr := hbm_h2c_cmd_2.bits.addr
	axi_port_2.aw.bits.len := hbm_h2c_cmd_2.bits.len
	// size?
	hbm_h2c_data_2.ready := axi_port_2.w.ready
	axi_port_2.w.valid := hbm_h2c_data_2.valid
	axi_port_2.w.bits.data := hbm_h2c_data_2.bits.data
	axi_port_2.w.bits.last := hbm_h2c_data_2.bits.last
	//back
	axi_port_2.b.ready := hbm_h2c_back_2.ready
	hbm_h2c_back_2.valid := axi_port_2.b.valid
	hbm_h2c_back_2.bits <> axi_port_2.b.bits

	// C2H 从 hbm 里读数据（应该是 ar
	hbm_c2h_cmd_2.ready := axi_port_2.ar.ready
	axi_port_2.ar.valid := hbm_c2h_cmd_2.valid
	axi_port_2.ar.bits.addr := hbm_c2h_cmd_2.bits.addr
	axi_port_2.ar.bits.len := hbm_c2h_cmd_2.bits.len

	axi_port_2.r.ready := hbm_c2h_data_2.ready
	hbm_c2h_data_2.valid := axi_port_2.r.valid
	hbm_c2h_data_2.bits.data := axi_port_2.r.bits.data
	hbm_c2h_data_2.bits.last := axi_port_2.r.bits.last

	h2c.io.start_addr	:= Cat(reg_control(100), reg_control(101))
	h2c.io.length		:= reg_control(102)
	h2c.io.offset		:= reg_control(103)
	h2c.io.sop			:= reg_control(104)
	h2c.io.eop			:= reg_control(105)
	h2c.io.start		:= reg_control(106)
	h2c.io.total_words	:= reg_control(107)
	h2c.io.total_qs		:= reg_control(108)
	h2c.io.total_cmds	:= reg_control(109)
	h2c.io.range		:= reg_control(110)
	h2c.io.range_words	:= reg_control(111)
	h2c.io.is_seq		:= reg_control(112)
	h2c.io.target_hbm	:= reg_control(113)
	h2c.io.target_addr	:= reg_control(114)

	for(i <- 0 until 16){
		h2c.io.count_word(i*32+31,i*32)	<> reg_status(102+i)
	}
	h2c.io.count_err	<> reg_status(100)
	h2c.io.count_time	<> reg_status(101)

	c2h.io.start_addr		:= Cat(reg_control(200), reg_control(201))
	c2h.io.length			:= reg_control(202)
	c2h.io.offset			:= reg_control(203)
	c2h.io.start			:= reg_control(204)
	c2h.io.total_words		:= reg_control(205)
	c2h.io.total_qs			:= reg_control(206)
	c2h.io.total_cmds		:= reg_control(207)
	c2h.io.pfch_tag			:= reg_control(209)
	c2h.io.tag_index		:= reg_control(210)
	c2h.io.target_hbm		:= reg_control(213)
	c2h.io.target_addr		:= reg_control(214)
	
	c2h.io.count_cmd		<> reg_status(200)
	c2h.io.count_word		<> reg_status(201)
	c2h.io.count_time		<> reg_status(202)

	Collector.connect_to_status_reg(reg_status, 400)

	val inst = Module(new ila_name(Seq(	
    // clock,				// qdma input
	// io.sysClk,			// hbm input
	// userClk,			// qdma input(equal to clock)
	hbm_clk,			// hbm output

	qdma.io.user_arstn,
	// h2c.reset,

	h2c.io.count_err,
	// h2c.io.count_word,
	c2h.io.count_cmd,
	c2h.io.count_word,

	qdma_h2c_cmd.bits.addr,
	// qdma_h2c_cmd.bits.len,
	qdma_h2c_data.bits.data,
	qdma_h2c_data.bits.last,

	qdma_c2h_cmd.bits.addr,
	// qdma_c2h_cmd.bits.len,
	qdma_c2h_data.bits.data,
	qdma_c2h_data.bits.last,

	h2c.io.h2c_cmd.valid,
	h2c.io.h2c_cmd.ready,
	c2h.io.c2h_cmd.valid,
	c2h.io.c2h_cmd.ready,
	h2c.io.h2c_aw_1.valid,
	h2c.io.h2c_w_1.valid,
	h2c.io.h2c_b_1.valid,
	h2c.io.h2c_aw_1.ready,
	h2c.io.h2c_w_1.ready,
	h2c.io.h2c_b_1.ready,
	c2h.io.c2h_ar_1.valid,
	c2h.io.c2h_r_1.valid,
	c2h.io.c2h_ar_1.ready,
	c2h.io.c2h_r_1.ready,

	h2c.io.h2c_aw_1.bits.addr,
	c2h.io.c2h_ar_1.bits.addr,
	h2c.io.h2c_w_1.bits.data,
	c2h.io.c2h_r_1.bits.data,

	h2c.io.h2c_aw_2.valid,
	h2c.io.h2c_w_2.valid,
	h2c.io.h2c_b_2.valid,
	h2c.io.h2c_aw_2.ready,
	h2c.io.h2c_w_2.ready,
	h2c.io.h2c_b_2.ready,
	c2h.io.c2h_ar_2.valid,
	c2h.io.c2h_r_2.valid,
	c2h.io.c2h_ar_2.ready,
	c2h.io.c2h_r_2.ready,

	h2c.io.h2c_aw_2.bits.addr,
	c2h.io.c2h_ar_2.bits.addr,
	h2c.io.h2c_w_2.bits.data,
	c2h.io.c2h_r_2.bits.data,
	
	hbm_h2c_data_1.valid,
	hbm_h2c_data_1.bits.data,
	hbm_h2c_cmd_1.valid,
	hbm_h2c_cmd_1.bits.addr,
	hbm_h2c_back_1.valid,
	hbm_c2h_data_1.valid,
	hbm_c2h_data_1.bits.data,
	hbm_c2h_cmd_1.valid,
	hbm_c2h_cmd_1.bits.addr,

	hbm_h2c_data_2.valid,
	hbm_h2c_data_2.bits.data,
	hbm_h2c_cmd_2.valid,
	hbm_h2c_cmd_2.bits.addr,
	hbm_h2c_back_2.valid,
	hbm_c2h_data_2.valid,
	hbm_c2h_data_2.bits.data,
	hbm_c2h_cmd_2.valid,
	hbm_c2h_cmd_2.bits.addr,

	// axi_port_1.ar.bits.addr,
	// axi_port_1.r.bits.data,
	// axi_port_1.aw.bits.addr,
	// axi_port_1.w.bits.data,
	// axi_port_1.b.bits.resp,
	// axi_port_1.b.bits.id,

	// axi_port_1.ar.valid,
	// // axi_port_1.r.valid,
	// axi_port_1.aw.valid,
	// axi_port_1.w.valid,
	// axi_port_1.b.valid,

	// axi_port_2.ar.bits.addr,
	// axi_port_2.r.bits.data,
	// axi_port_2.aw.bits.addr,
	// axi_port_2.w.bits.data,
	// axi_port_2.b.bits.resp,
	// axi_port_2.b.bits.id,

	// axi_port_2.ar.valid,
	// // axi_port_2.r.valid,
	// axi_port_2.aw.valid,
	// axi_port_2.w.valid,
	// axi_port_2.b.valid,


	)))
	inst.connect(clock)
}

class ila_name(seq:Seq[Data]) extends BaseILA(seq)