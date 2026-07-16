import { useMemo, useState } from "react";
import { Calculator, PiggyBank, Receipt, TrendDown } from "@phosphor-icons/react";

const SONIOX_REALTIME_PER_HOUR = 0.12;
const FLOW_PRO_MONTHLY = 15;
const FLOW_PRO_ANNUAL_MONTHLY_EQUIVALENT = 12;

const money = new Intl.NumberFormat("en-US", {
  style: "currency",
  currency: "USD",
  minimumFractionDigits: 2,
  maximumFractionDigits: 2,
});

export function SavingsCalculator() {
  const [hours, setHours] = useState(20);
  const values = useMemo(() => {
    const soniox = hours * SONIOX_REALTIME_PER_HOUR;
    const monthlySavings = FLOW_PRO_MONTHLY - soniox;
    const annualSavings = monthlySavings * 12;
    const annualPlanSavings = FLOW_PRO_ANNUAL_MONTHLY_EQUIVALENT - soniox;

    return {
      soniox,
      monthlySavings,
      annualSavings,
      threeYearSavings: annualSavings * 3,
      percentage: Math.round((monthlySavings / FLOW_PRO_MONTHLY) * 100),
      annualPlanSavings,
      annualPlanPercentage: Math.round((annualPlanSavings / FLOW_PRO_ANNUAL_MONTHLY_EQUIVALENT) * 100),
    };
  }, [hours]);

  const fill = `${((hours - 5) / (80 - 5)) * 100}%`;

  return (
    <div className="savings-calculator">
      <div className="savings-heading">
        <span className="savings-icon"><Calculator size={22} weight="bold" aria-hidden="true" /></span>
        <div>
          <span className="eyebrow eyebrow-light">WORKED EXAMPLE · SONIOX REALTIME vs WISPR FLOW PRO</span>
          <h3>{hours} hours of voice. <em>{money.format(values.monthlySavings)}</em> stays yours.</h3>
          <p>A concrete comparison: Wispr Flow Pro’s flat monthly plan versus Dictate Keyboard with no subscription plus metered Soniox realtime API usage. Drag to see your own volume.</p>
        </div>
      </div>

      <div className="savings-slider">
        <label htmlFor="monthly-hours">
          Monthly transcription
          <output htmlFor="monthly-hours">{hours} hours / month</output>
        </label>
        <input
          id="monthly-hours"
          type="range"
          min="5"
          max="80"
          step="5"
          value={hours}
          style={{ "--fill": fill }}
          onChange={(event) => setHours(Number(event.target.value))}
        />
        <div aria-hidden="true"><span>5h</span><span>80h</span></div>
      </div>

      <div className="savings-cards">
        <article className="savings-card savings-card-paid">
          <span><Receipt size={18} weight="bold" aria-hidden="true" />WISPR FLOW PRO</span>
          <strong>{money.format(FLOW_PRO_MONTHLY)}</strong>
          <small>per month · recurring subscription</small>
        </article>
        <article className="savings-card savings-card-dictate">
          <span><TrendDown size={18} weight="bold" aria-hidden="true" />DICTATE KEYBOARD + SONIOX</span>
          <strong>{money.format(values.soniox)}</strong>
          <small>per month · metered API usage only</small>
        </article>
        <article className="savings-card savings-card-keep">
          <span><PiggyBank size={18} weight="bold" aria-hidden="true" />YOU KEEP</span>
          <strong>{money.format(values.monthlySavings)}</strong>
          <small>per month · {values.percentage}% lower ongoing cost</small>
        </article>
      </div>

      <div className="savings-verdict" aria-live="polite">
        <p><strong>{money.format(values.annualSavings)} kept per year.</strong> At the same usage, the three-year difference is {money.format(values.threeYearSavings)}.</p>
        <span>Annual-plan check: against Wispr Flow Pro’s {money.format(FLOW_PRO_ANNUAL_MONTHLY_EQUIVALENT)}/mo annual-billing equivalent, the difference is still {money.format(values.annualPlanSavings)}/mo ({values.annualPlanPercentage}% lower).</span>
      </div>

      <p className="savings-source">
        Estimate checked July 2026. Soniox lists realtime speech-to-text at about $0.12/hour. Wispr Flow lists Pro at $15/user/month, or $144/year (≈$12/month). Compares the recurring subscription with transcription API usage only; excludes taxes, free credits, optional rewrite-model usage, and any store cost. <a href="https://soniox.com/pricing" target="_blank" rel="noreferrer">Soniox pricing</a> · <a href="https://wisprflow.ai/pricing" target="_blank" rel="noreferrer">Wispr Flow pricing</a>
      </p>
    </div>
  );
}
