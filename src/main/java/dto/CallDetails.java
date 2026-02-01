package dto;

import java.util.List;

public class CallDetails {
    private int amount;
    private List<String> details;

    public CallDetails(int amount, List<String> details) {
        this.amount = amount;
        this.details = details;
    }

    public int getAmount() {
        return amount;
    }

    public void setAmount(int amount) {
        this.amount = amount;
    }

    public void incrementAmount(){
        amount++;
    }

    public List<String> getDetails() {
        return details;
    }

    public void setDetails(List<String> details) {
        this.details = details;
    }
}
