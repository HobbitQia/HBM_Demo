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
	val in_axi = Wire(Flipped(new AXI(33, 256, 6, 0, 4)))
	ToZero(in_axi)
	val in_axi_regslice = withClockAndReset(userClk, !qdma.io.user_arstn) { AXIRegSlice(in_axi) }
	in_axi.aw <> h2c.io.h2c_aw
	in_axi.w <> h2c.io.h2c_w
	in_axi.ar <> c2h.io.c2h_ar
	c2h.io.c2h_r <> in_axi.r
	h2c.io.h2c_b <> in_axi.b

	val out_axi = XAXIConverter(in_axi_regslice, userClk, qdma.io.user_arstn, hbm_clk, hbm_rstn)
	val out_axi_regslice = withClockAndReset(hbm_clk, !hbm_rstn) { AXIRegSlice(out_axi) }

	val hbm_h2c_cmd = out_axi_regslice.aw

	val hbm_h2c_data = out_axi_regslice.w

	val hbm_c2h_cmd = out_axi_regslice.ar

	val hbm_c2h_data = Wire(Decoupled(new AXI_DATA_R(33, 256, 6, 0)))
	ToZero(hbm_c2h_data)
	val hbm_h2c_back = Wire(Decoupled(new AXI_BACK(33, 256, 6, 0)))

	// out_axi_regslice.r <> hbm_c2h_data
	out_axi_regslice.r.bits := hbm_c2h_data.bits
	out_axi_regslice.r.valid := hbm_c2h_data.valid
	hbm_c2h_data.ready := out_axi_regslice.r.ready

	out_axi_regslice.b.bits := hbm_h2c_back.bits
	out_axi_regslice.b.valid := hbm_h2c_back.valid
	hbm_h2c_back.ready := out_axi_regslice.b.ready
	
	// H2C 往 hbm 里写数据（应该是 aw 和 w
	val axi_port_0 = hbm_driver.io.axi_hbm(0)
	hbm_h2c_cmd.ready := axi_port_0.aw.ready
	axi_port_0.aw.valid := hbm_h2c_cmd.valid
	axi_port_0.aw.bits.addr := hbm_h2c_cmd.bits.addr
	axi_port_0.aw.bits.len := hbm_h2c_cmd.bits.len
	// hbm_h2c_cmd.bits <> axi_port_0.aw.bits		// addr len=
	// size?
	hbm_h2c_data.ready := axi_port_0.w.ready
	axi_port_0.w.valid := hbm_h2c_data.valid
	axi_port_0.w.bits.data := hbm_h2c_data.bits.data
	axi_port_0.w.bits.last := hbm_h2c_data.bits.last
	// hbm_h2c_data.bits <> axi_port_0.w.bits		// data last
	//back
	axi_port_0.b.ready := hbm_h2c_back.ready
	hbm_h2c_back.valid := axi_port_0.b.valid
	hbm_h2c_back.bits <> axi_port_0.b.bits

	// C2H 从 hbm 里读数据（应该是 ar
	hbm_c2h_cmd.ready := axi_port_0.ar.ready
	axi_port_0.ar.valid := hbm_c2h_cmd.valid
	axi_port_0.ar.bits.addr := hbm_c2h_cmd.bits.addr
	axi_port_0.ar.bits.len := hbm_c2h_cmd.bits.len
	// hbm_c2h_cmd.bits <> axi_port_0.ar.bits		// addr len


	axi_port_0.r.ready := hbm_c2h_data.ready
	hbm_c2h_data.valid := axi_port_0.r.valid
	hbm_c2h_data.bits.data := axi_port_0.r.bits.data
	hbm_c2h_data.bits.last := axi_port_0.r.bits.last
	// hbm_c2h_data.bits <> axi_port_0.r.bits		// data last

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
	io.sysClk,			// hbm input
	userClk,			// qdma input(equal to clock)
	hbm_clk,			// hbm output

	qdma.io.user_arstn,
	h2c.reset,

	qdma_h2c_cmd.bits.addr,
	qdma_h2c_cmd.bits.len,
	qdma_h2c_data.bits.data,
	qdma_h2c_data.bits.last,

	qdma_c2h_cmd.bits.addr,
	qdma_c2h_cmd.bits.len,
	qdma_c2h_data.bits.data,
	qdma_c2h_data.bits.last,

	h2c.io.h2c_cmd.valid,
	h2c.io.h2c_cmd.ready,
	c2h.io.c2h_cmd.valid,
	c2h.io.c2h_cmd.ready,

	h2c.io.h2c_aw.bits.addr,
	c2h.io.c2h_ar.bits.addr,
	h2c.io.h2c_w.bits.data,
	c2h.io.c2h_r.bits.data,

	axi_port_0.ar.bits.addr,
	axi_port_0.r.bits.data,
	axi_port_0.aw.bits.addr,
	axi_port_0.w.bits.data,
	axi_port_0.b.bits.resp,
	axi_port_0.b.bits.id,

	axi_port_0.ar.valid,
	axi_port_0.r.valid,
	axi_port_0.aw.valid,
	axi_port_0.w.valid,
	axi_port_0.b.valid,

	axi_port_0.ar.ready,
	axi_port_0.r.ready,
	axi_port_0.aw.ready,
	axi_port_0.w.ready,
	axi_port_0.b.ready,

	hbm_h2c_cmd.bits.addr,
	hbm_c2h_cmd.bits.addr,
	hbm_h2c_data.bits.data,
	hbm_c2h_data.bits.data,

	in_axi.ar.bits.addr,
	in_axi.r.bits.data,
	in_axi.aw.bits.addr,
	in_axi.w.bits.data,
	in_axi.b.bits.resp,

	in_axi_regslice.ar.bits.addr,
	in_axi_regslice.r.bits.data,
	in_axi_regslice.aw.bits.addr,
	in_axi_regslice.w.bits.data,
	in_axi_regslice.b.bits.resp,

	out_axi.ar.bits.addr,
	out_axi.r.bits.data,
	out_axi.aw.bits.addr,
	out_axi.w.bits.data,
	out_axi.b.bits.resp,

	// out_axi_regslice.ar.bits.addr,
	// out_axi_regslice.r.bits.data,
	// out_axi_regslice.aw.bits.addr,
	// out_axi_regslice.w.bits.data,
	out_axi_regslice.b.bits.resp
	)))
	inst.connect(clock)
}

class ila_name(seq:Seq[Data]) extends BaseILA(seq)