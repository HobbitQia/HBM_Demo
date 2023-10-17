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
		QDMA_SLAVE_BRIDGE = true, 
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
		SLAVE_BRIDGE		= true,
		BRIDGE_BAR_SCALE	= "Megabytes",
		BRIDGE_BAR_SIZE 	= 4
	))
	
	qdma.io.s_axib.get  <> DontCare

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
	
	val axi_slave = withClockAndReset(userClk, ~userRstn.asBool) {Module(new SimpleAXISlave(new AXIB))}
	axi_slave.io.axi	<> qdma.io.axib

	val r_data = axi_slave.io.axi.r.bits.data(31,0)

    val count_w_fire = withClockAndReset(userClk, ~userRstn.asBool) {RegInit(0.U(32.W))}
    when(qdma.io.axib.w.fire()){
        count_w_fire	:= count_w_fire+1.U
    }
    qdma.io.reg_status(0)	:= count_w_fire

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
	c2h.io.c2h_data		<> DontCare

	// 还需要一个 FIFO
	val fifo_h2c_cmd		= XConverter(new AXI_ADDR(33, 256, 6, 0, 4), userClk, qdma.io.user_arstn, hbm_clk)
	fifo_h2c_cmd.io.in <> withClockAndReset(userClk, !qdma.io.user_arstn) { RegSlice(h2c.io.h2c_aw) }
	val hbm_h2c_cmd = withClockAndReset(hbm_clk, !hbm_rstn) { RegSlice(fifo_h2c_cmd.io.out) } 
	// 这里应该是 in

	val fifo_h2c_data		= XConverter(new AXI_DATA_W(33, 256, 6, 0), userClk, qdma.io.user_arstn, hbm_clk)
	// ToZero(fifo_h2c_data.io.in.bits)
	fifo_h2c_data.io.in <> withClockAndReset (userClk, !qdma.io.user_arstn) { RegSlice(h2c.io.h2c_w) }
	val hbm_h2c_data = withClockAndReset(hbm_clk, !hbm_rstn) { RegSlice(fifo_h2c_data.io.out) }

	val fifo_c2h_cmd		= XConverter(new AXI_ADDR(33, 256, 6, 0, 4), userClk, qdma.io.user_arstn, hbm_clk)
	fifo_c2h_cmd.io.in <> withClockAndReset(userClk, !qdma.io.user_arstn) { RegSlice(c2h.io.c2h_ar) }
	val hbm_c2h_cmd =  withClockAndReset(hbm_clk, !hbm_rstn) { RegSlice(fifo_c2h_cmd.io.out) }

	// 能不能用 AXIRegSlice
	val fifo_c2h_data		= XConverter(new AXI_DATA_R(33, 256, 6, 0), hbm_clk, hbm_rstn, userClk)
	val hbm_c2h_data = Wire(Decoupled(new AXI_DATA_R(33, 256, 6, 0)))
	ToZero(hbm_c2h_data)
	fifo_c2h_data.io.in <> withClockAndReset(hbm_clk, !hbm_rstn) { RegSlice(hbm_c2h_data) }
	c2h.io.c2h_r <> withClockAndReset (userClk, !qdma.io.user_arstn) { RegSlice(fifo_c2h_data.io.out) }

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
    clock,				// qdma input
	io.sysClk,			// hbm input
	userClk,			// qdma input(equal to clock)
	hbm_clk,			// hbm output

	reset,
	userRstn,
	hbm_rstn,
	qdma.io.user_arstn,
	qdma_h2c_cmd.bits.addr,
	qdma_h2c_cmd.bits.len,
	qdma_h2c_data.bits.data,
	qdma_h2c_data.bits.last,

	qdma_c2h_cmd.bits.addr,
	qdma_c2h_cmd.bits.len,
	qdma_c2h_data.bits.data,
	qdma_c2h_data.bits.last,
	)))
	inst.connect(clock)
}

class ila_name(seq:Seq[Data]) extends BaseILA(seq)