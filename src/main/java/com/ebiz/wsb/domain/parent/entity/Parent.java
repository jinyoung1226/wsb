package com.ebiz.wsb.domain.parent.entity;

import com.ebiz.wsb.domain.group.entity.Group;
import com.ebiz.wsb.domain.route.entity.Route;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.DynamicInsert;

@Entity
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
@DynamicInsert
@Table(name = "Parent")
public class Parent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "name")
    private String name;

    @Column(name="email")
    private String email;

    @Column(name="password")
    private String password;

    @Column(name="phone")
    private String phone;

    @Column(name = "address")
    private String address;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "group_id")
    private Group group;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "route_id")
    private Route route;

    @Column(name = "image_path")
    private String imagePath;
}
