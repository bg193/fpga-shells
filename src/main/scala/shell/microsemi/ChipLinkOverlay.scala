// See LICENSE for license details.
package sifive.fpgashells.shell.microsemi

import chisel3._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.util._
import sifive.fpgashells.shell._
import sifive.fpgashells.ip.microsemi._

abstract class ChipLinkPolarFireOverlay(params: ChipLinkOverlayParams)
  extends ChipLinkOverlay(params, rxPhase=240, txPhase=31.5)
{
  def shell: PolarFireShell

  override def fpgaReset = true

  shell { InModuleBody {
    val (tx, _) = txClock.in(0)
    val (rx, _) = rxI.out(0)
    val rxEdge = rxI.edges.out(0)

    // Provide reset pulse to initialize b2c_reset (before RX PLL locks)
    ioSink.io.fpga_reset.foreach { _ := !shell.initMonitor.io.DEVICE_INIT_DONE }

    // !!! add a DDR pad
    io.c2b.clk := tx.clock

    val clkint = Module(new CLKINT)
    clkint.suggestName(s"${name}_rx_clkint")
    clkint.io.A := io.b2c.clk
    rx.clock := clkint.io.Y

    // Consume as much slack for safey for trace jitter+skew on both sides as possible
    val txMargin = 1.5
    val rxMargin = 0.0 // <- we badly need a PLL with external feedback!

    // At 125MHz, 6 corner analysis yields these worst min/max corners:
    //    RX min_slow_lv_lt: 0.038ns slack + 0!!! margin
    //       max_fast_hv_lt: 0.089ns slack + 0!!! margin
    //    TX min_fast_hv_lt: 0.086ns slack + 1.5 margin
    //       max_slow_lv_lt: 0.024ns slack + 1.5 margin

    // We have to add a these constants to work around some Libero timing analysis bug
    val periodNs = 1000.0 / rxEdge.clock.freqMHz
    val rxHack = 5

    val timing = IOTiming(
      /* The data signals coming from Aloe have: clock - 1.2 <= transition <= clock + 0.8
       *   min = hold           = - 1.2
       *   max = period - setup =   0.8
       */
      minInput  = -1.2 - rxMargin + periodNs + rxHack,
      maxInput  =  0.8 + rxMargin + periodNs + rxHack,
      /* The data signals going to Aloe must have: clock - 1.85 <= NO transition <= clock + 0.65
       *   min = -hold = -0.65
       *   max = setup =  1.85
       */
      minOutput = -0.65 - txMargin,
      maxOutput =  1.85 + txMargin)

    shell.sdc.addClock(sdcRxClockName, io.b2c.clk, rxEdge.clock.freqMHz)
    shell.sdc.addDerivedClock(sdcTxClockName, "[ get_pins {corePLL/corePLL_0/pll_inst_0/OUT1} ]", io.c2b.clk)
    IOPin.of(io).filter(p => p.isInput  && !(p.element eq io.b2c.clk)).foreach { e =>
      shell.sdc.addIOTiming(e, sdcRxClockName, timing)
    }
    IOPin.of(io).filter(p => p.isOutput && !(p.element eq io.c2b.clk)).foreach { e =>
      shell.sdc.addIOTiming(e, sdcTxClockName, timing)
    }
  } }
}
