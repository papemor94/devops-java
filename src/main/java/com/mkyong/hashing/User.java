package com.mkyong.hashing;


import java.util.Objects;

public class User {

    private String firstname ;
    private String lastname  ;
    private String email ;
    private double telephone  ;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        User user = (User) o;
        return Double.compare(user.telephone, telephone) == 0 &&
                Objects.equals(firstname, user.firstname) &&
                Objects.equals(lastname, user.lastname) &&
                Objects.equals(email, user.email);
    }

    @Override
    public int hashCode() {
        return Objects.hash(firstname, lastname, email, telephone);
    }

    public String getFirstname() {
        return firstname;
    }

    public void setFirstname(String firstname) {
        this.firstname = firstname;
    }

    public String getLastname() {
        return lastname;
    }

    public void setLastname(String lastname) {
        this.lastname = lastname;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public double getTelephone() {
        return telephone;
    }

    public void setTelephone(double telephone) {
        this.telephone = telephone;
    }
}
