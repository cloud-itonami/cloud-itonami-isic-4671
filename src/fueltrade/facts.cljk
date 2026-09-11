(ns fueltrade.facts
  "Per-jurisdiction downstream fuel-wholesale regulatory catalog -- the
  G2-style spec-basis table the Fuel Trading Governor checks every
  `:contract/verify` proposal against ('did the advisor cite an
  OFFICIAL public source for this jurisdiction's fuel-wholesale /
  sanctions / fuel-excise requirements, or did it invent one?').

  Each entry below is a REAL jurisdiction with a REAL downstream fuel-
  wholesale regime: Japan's Ministry of Finance (MOF) Customs / METI
  jurisdiction over wholesale fuel trade (関税法; 輸出貿易管理令), the US
  OFAC (Treasury) sanctions programs plus the IRS fuel excise tax
  (26 U.S.C. §4081), the UK HM Revenue & Customs (HMRC) / Office of
  Financial Sanctions Implementation (OFSI) regime (Excise Notice 179;
  UK sanctions), and Customs Norway / the Norwegian MFA's sanctions
  regulations. The required-evidence set (credit-clearance record,
  contract / purchase order, sanctions-screening (OFAC/equivalent)
  record) mirrors the counterparty-diligence evidence a fuel-wholesale
  regulator and a counterparty-credit / compliance function actually
  demand before bulk fuel is dispatched at the wholesale rack and
  invoiced.

  Coverage is reported HONESTLY (see `coverage`), the same discipline
  every sibling actor's `facts` namespace uses: a jurisdiction not in
  this table has NO spec-basis, full stop -- the advisor must not
  fabricate one, and the governor holds if it tries.")

(def catalog
  "iso3 -> requirement map. `:required-evidence` is the counterparty-
  diligence evidence set (credit-clearance record, contract/PO,
  sanctions-screening record); `:legal-basis` / `:owner-authority` /
  `:provenance` are the G2 citation the governor requires before any
  `:contract/verify` proposal can commit."
  {"JPN" {:name "JPN"
          :owner-authority "財務省 (MOF) 関税局 / 経済産業省"
          :legal-basis "関税法 (Customs Act); 輸出貿易管理令 (Foreign Exchange and Foreign Trade Control Act / Export Trade Control Order)"
          :provenance "https://www.customs.go.jp/"
          :required-evidence ["credit-clearance record"
                              "contract/PO"
                              "sanctions-screening (OFAC/equivalent) record"]}
   "USA" {:name "USA"
          :owner-authority "OFAC (U.S. Treasury) / IRS"
          :legal-basis "OFAC sanctions programs; fuel excise tax (26 U.S.C. §4081)"
          :provenance "https://ofac.treasury.gov/"
          :required-evidence ["credit-clearance record"
                              "contract/PO"
                              "sanctions-screening (OFAC/equivalent) record"]}
   "GBR" {:name "GBR"
          :owner-authority "HM Revenue & Customs (HMRC) / Office of Financial Sanctions Implementation (OFSI)"
          :legal-basis "Excise Notice 179; UK financial sanctions regulations"
          :provenance "https://www.gov.uk/government/organisations/hm-revenue-customs"
          :required-evidence ["credit-clearance record"
                              "contract/PO"
                              "sanctions-screening (OFAC/equivalent) record"]}
   "NOR" {:name "NOR"
          :owner-authority "Customs Norway (Tollvesenet) / Ministry of Foreign Affairs"
          :legal-basis "Customs Act (Tollloven); Norway sanctions regulations"
          :provenance "https://www.toll.no/en/"
          :required-evidence ["credit-clearance record"
                              "contract/PO"
                              "sanctions-screening (OFAC/equivalent) record"]}})

(defn spec-basis
  "The jurisdiction's requirement map, or nil -- nil means NO spec-basis,
  and the governor must hold any proposal that tries to dispatch fuel
  or settle an invoice on it."
  [iso3]
  (get catalog iso3))

(defn coverage
  "Honest coverage report: how many of the requested jurisdictions
  actually have a spec-basis entry. Never report a missing jurisdiction
  as covered."
  ([] (coverage (keys catalog)))
  ([iso3s]
   (let [have (filter catalog iso3s)
         missing (remove catalog iso3s)]
     {:requested (count iso3s)
      :covered (count have)
      :covered-jurisdictions (vec (sort have))
      :missing-jurisdictions (vec (sort missing))
      :note (str "cloud-itonami-isic-4671 R0: " (count catalog)
                 " jurisdictions seeded with an official spec-basis. "
                 "This is a starting catalog, not a survey of all ~194 "
                 "jurisdictions -- extend `fueltrade.facts/catalog`, "
                 "never fabricate a jurisdiction's requirements.")})))

(defn required-evidence-satisfied?
  "Does `submitted` (a set/coll of evidence keywords or strings) satisfy
  every evidence item listed for `iso3`? Missing spec-basis -> never
  satisfied."
  [iso3 submitted]
  (when-let [{:keys [required-evidence]} (spec-basis iso3)]
    (let [need (count required-evidence)
          have (count (filter (set submitted) required-evidence))]
      (= need have))))

(defn evidence-checklist [iso3]
  (:required-evidence (spec-basis iso3) []))
