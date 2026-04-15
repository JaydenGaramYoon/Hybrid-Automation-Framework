# Test case documents (LogIn, SignUp, Cart, CheckOut)

One markdown file per **`tc_id`** in `testData/*.xlsx` (Sheet1).

**How to write and review these files** — Conventions (user-story titles, Excel as source of truth, `## Steps`, what to omit) are documented in the **repository root [`README.md`](../../README.md)**, section **Manual test cases (markdown)**.

## Index

Titles follow the user-story pattern: **As a** [role], **I want** [goal], **so that** [benefit].

| TC ID | Title (user story) | Data file |
| --- | --- | --- |
| [TC001](TC001.md) | As a registered user, I want to sign in with valid email and password so that I can access my account. | LogIn.xlsx |
| [TC002](TC002.md) | As a user, I want clear feedback when my email format is invalid on sign-in so that I can correct it before trying again. | LogIn.xlsx |
| [TC003](TC003.md) | As a user, I want to see an error when my password is wrong so that I know sign-in did not succeed. | LogIn.xlsx |
| [TC004](TC004.md) | As a new visitor, I want to register with valid profile details so that I can create an account. | SignUp.xlsx |
| [TC005](TC005.md) | As a user signing up, I want the form to reject an invalid email format so that I cannot submit unusable contact details. | SignUp.xlsx |
| [TC006](TC006.md) | As a user signing up, I want the form to enforce password complexity rules so that my password meets security requirements. | SignUp.xlsx |
| [TC007](TC007.md) | As a user signing up, I want validation when my phone number is too short so that I provide a complete number. | SignUp.xlsx |
| [TC008](TC008.md) | As a user signing up, I want validation when my phone contains invalid characters so that only acceptable input is accepted. | SignUp.xlsx |
| [TC009](TC009.md) | As a user signing up, I want validation when my phone is not numeric so that I fix the format before submitting. | SignUp.xlsx |
| [TC010](TC010.md) | As a logged-in shopper, I want to see products I added on the cart page so that I can confirm my selection before checkout. | Cart.xlsx |
| [TC011](TC011.md) | As a logged-in shopper, I want the cart to show only items I actually added so that I am not misled by products I did not choose. | Cart.xlsx |
| [TC012](TC012.md) | As a shopper completing checkout, I want to see a clear order confirmation message so that I know my order was placed successfully. | CheckOut.xlsx |

**Notes**

- Rows are identified by **`tc_id`**. Filter columns (**valid** / **invalid**, etc.) in spreadsheets support automation and data maintenance; they are not re-explained in every TC file.
